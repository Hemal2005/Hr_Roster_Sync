package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.AppliedEventLog;
import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.HrEvent;
import com.moveinsync.rostersync.domain.PayloadHash;
import com.moveinsync.rostersync.domain.PayloadHasher;
import com.moveinsync.rostersync.domain.StoredVersion;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.VersionDecision;
import com.moveinsync.rostersync.domain.VersionPolicy;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.domain.validation.RecordValidator;
import com.moveinsync.rostersync.domain.validation.ValidationError;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one HR record: validate, then in its own transaction lock the employee row,
 * let the version policy decide, write the HR columns and record the applied change.
 * Each record gets its own transaction so one failure cannot undo the others in a batch.
 */
@Service
public class ApplyService {

	private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

	private final RecordValidator validator;
	private final EmployeeRepository employees;
	private final AppliedEventLog appliedEvents;
	private final TransactionTemplate tx;
	private final VersionPolicy versionPolicy = new VersionPolicy();
	private final PayloadHasher hasher = new PayloadHasher();

	ApplyService(RecordValidator validator, EmployeeRepository employees, AppliedEventLog appliedEvents,
			TransactionTemplate tx) {
		this.validator = validator;
		this.employees = employees;
		this.appliedEvents = appliedEvents;
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
		}
	}

	private ApplyResult decideAndWrite(HrEvent event, PayloadHash hash) {
		Optional<Employee> stored = employees.findForUpdate(event.tenantId(), event.employeeId());
		VersionDecision decision = versionPolicy.decide(stored.map(StoredVersion::of), event.version(), hash);
		return switch (decision) {
			case STALE -> ApplyResult.of(ApplyOutcome.STALE);
			case DUPLICATE -> ApplyResult.of(ApplyOutcome.DUPLICATE);
			case CONFLICT -> ApplyResult.rejected(ErrorCode.VERSION_CONFLICT);
			case APPLY -> {
				if (stored.isEmpty()) {
					employees.insert(event.tenantId(), Employee.createFrom(event, hash));
				} else {
					Employee employee = stored.get();
					employee.applyHr(event, hash);
					if (!employees.updateHrFields(event.tenantId(), employee)) {
						throw new IllegalStateException("locked row changed version during apply");
					}
				}
				appliedEvents.recordApplied(event.tenantId(), event.sourceSystem(), event.employeeId(), event.version());
				yield ApplyResult.of(ApplyOutcome.APPLIED);
			}
		};
	}
}
