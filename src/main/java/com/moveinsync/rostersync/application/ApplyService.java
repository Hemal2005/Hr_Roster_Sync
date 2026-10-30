package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.AppliedEventLog;
import com.moveinsync.rostersync.domain.ChangeEvent;
import com.moveinsync.rostersync.domain.ChangeEventType;
import com.moveinsync.rostersync.domain.DeactivateEmployee;
import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeField;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.EventHeader;
import com.moveinsync.rostersync.domain.HrEvent;
import com.moveinsync.rostersync.domain.Outbox;
import com.moveinsync.rostersync.domain.PayloadHash;
import com.moveinsync.rostersync.domain.PayloadHasher;
import com.moveinsync.rostersync.domain.StoredVersion;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.VersionDecision;
import com.moveinsync.rostersync.domain.VersionPolicy;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import com.moveinsync.rostersync.domain.validation.ValidationError;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one HR record: validate, then in its own transaction lock the employee row,
 * let the version policy decide, write the HR columns, record the applied change and
 * enqueue its change event. The update and the event commit or roll back together.
 * Each record gets its own transaction so one failure cannot undo the others in a batch.
 */
@Service
public class ApplyService {

	private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

	private final RecordValidator validator;
	private final EmployeeRepository employees;
	private final AppliedEventLog appliedEvents;
	private final Outbox outbox;
	private final TransactionTemplate tx;
	private final VersionPolicy versionPolicy = new VersionPolicy();
	private final PayloadHasher hasher = new PayloadHasher();

	ApplyService(RecordValidator validator, EmployeeRepository employees, AppliedEventLog appliedEvents,
			Outbox outbox, TransactionTemplate tx) {
		this.validator = validator;
		this.employees = employees;
		this.appliedEvents = appliedEvents;
		this.outbox = outbox;
		this.tx = tx;
	}

	public ApplyResult apply(TenantId tenantId, String sourceSystem, IncomingHrRecord record) {
		MDC.put("tenantId", tenantId.value());
		if (record.employeeId() != null && !record.employeeId().isBlank()) {
			MDC.put("employeeId", record.employeeId());
		}
		try {
			ApplyResult result = validateAndApply(tenantId, sourceSystem, record);
			if (result.outcome() == ApplyOutcome.REJECTED) {
				log.info("hr event rejected version={} code={}", record.version(), result.errorCode());
			} else {
				log.info("hr event {} version={}", result.outcome(), record.version());
			}
			return result;
		} finally {
			MDC.remove("tenantId");
			MDC.remove("employeeId");
		}
	}

	/**
	 * Deactivates an employee the nightly export no longer lists, but only if nothing
	 * happened to them since the plan was made: still ACTIVE, still at
	 * {@code expectedVersion}, and last changed no later than the export's {@code asOf}.
	 * Returns false, changing nothing, otherwise.
	 */
	public boolean deactivateMissing(TenantId tenantId, EmployeeId employeeId, long expectedVersion, Instant asOf) {
		MDC.put("tenantId", tenantId.value());
		MDC.put("employeeId", employeeId.value());
		try {
			Boolean done = tx.execute(status -> {
				Optional<Employee> stored = employees.findForUpdate(tenantId, employeeId);
				if (stored.isEmpty()) {
					return false;
				}
				Employee employee = stored.get();
				if (employee.status() != EmployeeStatus.ACTIVE || employee.hrVersion() != expectedVersion
						|| employee.lastHrEventAt().isAfter(asOf)) {
					return false;
				}
				var header = new EventHeader(tenantId, ReconciliationSource.NAME, employeeId, expectedVersion, asOf);
				Set<EmployeeField> changed = employee.deactivateAsMissingFromExport(
						hasher.hash(new DeactivateEmployee(header, employee.hrFields())));
				if (!employees.markInactiveAtSameVersion(tenantId, employee)) {
					return false;
				}
				outbox.enqueue(ChangeEvent.of(employee, ChangeEventType.EMPLOYEE_DEACTIVATED, changed, asOf));
				return true;
			});
			log.info("missing from export: {}", Boolean.TRUE.equals(done) ? "deactivated" : "skipped, changed since plan");
			return Boolean.TRUE.equals(done);
		} finally {
			MDC.remove("tenantId");
			MDC.remove("employeeId");
		}
	}

	private ApplyResult validateAndApply(TenantId tenantId, String sourceSystem, IncomingHrRecord record) {
		List<ValidationError> errors = validator.validate(tenantId, record);
		if (!errors.isEmpty()) {
			return ApplyResult.rejected(errors.getFirst().code());
		}
		HrEvent event = record.toEvent(tenantId, sourceSystem);
		PayloadHash hash = hasher.hash(event);
		try {
			try {
				return tx.execute(status -> decideAndWrite(event, hash));
			} catch (DuplicateKeyException firstInsertLostARace) {
				// Two first events for a new employee both saw no row; the other one inserted
				// it. The row exists now, so the retry locks it and decides normally.
				return tx.execute(status -> decideAndWrite(event, hash));
			}
		} catch (DomainException e) {
			return ApplyResult.rejected(e.code());
		} catch (RuntimeException e) {
			// The transaction rolled back, so nothing of this record was written. Report it
			// as retryable on this record alone instead of failing the whole batch. Class and
			// location only: the message can contain the values that caused it.
			StackTraceElement origin = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
			log.error("hr event processing failed {} at {}", e.getClass().getName(), origin);
			return ApplyResult.rejected(ErrorCode.PROCESSING_FAILED);
		}
	}

	private ApplyResult decideAndWrite(HrEvent event, PayloadHash hash) {
		Optional<Employee> stored = employees.findForUpdate(event.tenantId(), event.employeeId());
		EmployeeStatus statusBefore = stored.map(Employee::status).orElse(null);
		VersionDecision decision = versionPolicy.decide(stored.map(StoredVersion::of), event.version(), hash);
		return switch (decision) {
			case STALE -> ApplyResult.of(ApplyOutcome.STALE);
			case DUPLICATE -> ApplyResult.of(ApplyOutcome.DUPLICATE);
			case CONFLICT -> ApplyResult.rejected(ErrorCode.VERSION_CONFLICT);
			case APPLY -> {
				Employee employee;
				Set<EmployeeField> changed;
				if (stored.isEmpty()) {
					employee = Employee.createFrom(event, hash);
					employees.insert(event.tenantId(), employee);
					changed = EnumSet.copyOf(employee.hrFields().changedFieldsSince(null));
					changed.add(EmployeeField.STATUS);
				} else {
					employee = stored.get();
					changed = employee.applyHr(event, hash);
					if (!employees.updateHrFields(event.tenantId(), employee)) {
						throw new IllegalStateException("locked row changed version during apply");
					}
				}
				appliedEvents.recordApplied(event.tenantId(), event.sourceSystem(), event.employeeId(), event.version());
				ChangeEventType type = ChangeEventType.forHrChange(stored.isEmpty()
						? Optional.empty()
						: Optional.of(statusBefore), employee.status());
				outbox.enqueue(ChangeEvent.of(employee, type, changed, event.occurredAt()));
				yield ApplyResult.of(ApplyOutcome.APPLIED);
			}
		};
	}
}
