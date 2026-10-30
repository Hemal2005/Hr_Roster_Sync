package com.moveinsync.rostersync.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The employee aggregate. State changes only through {@link #applyHr} and
 * {@link #applyTransport}; there are no setters. The two paths touch disjoint field
 * groups and keep separate versions, so neither owner can overwrite or invalidate the
 * other's data. Deciding whether an HR event should be applied at all (stale,
 * duplicate, conflict) is the caller's job via the version policy; this class guards
 * the invariant that each version only moves forward.
 */
public final class Employee {

	private final TenantId tenantId;
	private final EmployeeId employeeId;
	private HrOwnedFields hrFields;
	private EmployeeStatus status;
	private long hrVersion;
	private PayloadHash hrPayloadHash;
	private Instant lastHrEventAt;
	private TransportOwnedFields transportFields = TransportOwnedFields.NONE;
	private long transportVersion;

	private Employee(TenantId tenantId, EmployeeId employeeId) {
		this.tenantId = tenantId;
		this.employeeId = employeeId;
	}

	/**
	 * Builds a new employee from the first event seen for it. An update is accepted
	 * because events carry the full HR state, so a lost create costs nothing. An exit is
	 * accepted only if it carries the full state; a header-only exit has nothing to
	 * build the employee from.
	 */
	public static Employee createFrom(HrEvent event, PayloadHash hash) {
		Objects.requireNonNull(hash, "hash");
		Employee employee = new Employee(event.tenantId(), event.employeeId());
		switch (event) {
			case CreateEmployee create -> {
				employee.hrFields = create.fields();
				employee.status = EmployeeStatus.ACTIVE;
			}
			case UpdateEmployee update -> {
				employee.hrFields = update.fields();
				employee.status = EmployeeStatus.ACTIVE;
			}
			case DeactivateEmployee deactivate -> {
				if (!deactivate.carriesFullState()) {
					throw new DomainException(ErrorCode.UNKNOWN_EMPLOYEE);
				}
				employee.hrFields = deactivate.fields();
				employee.status = EmployeeStatus.INACTIVE;
			}
		}
		employee.recordHrEvent(event, hash);
		return employee;
	}

	/**
	 * Rebuilds an employee from storage. Not for new employees: those come from
	 * {@link #createFrom}.
	 */
	public static Employee restore(TenantId tenantId, EmployeeId employeeId, HrOwnedFields hrFields,
			EmployeeStatus status, long hrVersion, PayloadHash hrPayloadHash, Instant lastHrEventAt,
			TransportOwnedFields transportFields, long transportVersion) {
		Employee employee = new Employee(Objects.requireNonNull(tenantId, "tenantId"),
				Objects.requireNonNull(employeeId, "employeeId"));
		if (hrVersion < 1 || transportVersion < 0) {
			throw new IllegalArgumentException("stored versions out of range");
		}
		employee.hrFields = Objects.requireNonNull(hrFields, "hrFields");
		employee.status = Objects.requireNonNull(status, "status");
		employee.hrVersion = hrVersion;
		employee.hrPayloadHash = Objects.requireNonNull(hrPayloadHash, "hrPayloadHash");
		employee.lastHrEventAt = Objects.requireNonNull(lastHrEventAt, "lastHrEventAt");
		employee.transportFields = Objects.requireNonNull(transportFields, "transportFields");
		employee.transportVersion = transportVersion;
		return employee;
	}

	/**
	 * Applies a newer HR event and returns the names of the fields it changed.
	 */
	public Set<EmployeeField> applyHr(HrEvent event, PayloadHash hash) {
		Objects.requireNonNull(hash, "hash");
		if (!event.tenantId().equals(tenantId) || !event.employeeId().equals(employeeId)) {
			throw new IllegalArgumentException("event is for a different employee");
		}
		if (event.version() <= hrVersion) {
			throw new IllegalStateException(
					"version " + event.version() + " is not newer than stored " + hrVersion);
		}
		Set<EmployeeField> changed = EnumSet.noneOf(EmployeeField.class);
		switch (event) {
			case CreateEmployee create -> {
				changed.addAll(replaceHrFields(create.fields()));
				changed.addAll(changeStatus(EmployeeStatus.ACTIVE));
			}
			case UpdateEmployee update -> {
				changed.addAll(replaceHrFields(update.fields()));
				changed.addAll(changeStatus(EmployeeStatus.ACTIVE));
			}
			case DeactivateEmployee deactivate -> {
				if (deactivate.carriesFullState()) {
					changed.addAll(replaceHrFields(deactivate.fields()));
				}
				changed.addAll(changeStatus(EmployeeStatus.INACTIVE));
			}
		}
		recordHrEvent(event, hash);
		return changed;
	}

	/**
	 * Replaces the transport-owned fields if {@code expectedVersion} matches the stored
	 * transport version (optimistic check, like an HTTP If-Match), then bumps it.
	 */
	public Set<EmployeeField> applyTransport(TransportOwnedFields incoming, long expectedVersion) {
		Objects.requireNonNull(incoming, "incoming");
		if (expectedVersion != transportVersion) {
			throw new DomainException(ErrorCode.TRANSPORT_VERSION_MISMATCH);
		}
		Set<EmployeeField> changed = incoming.changedFieldsSince(transportFields);
		transportFields = incoming;
		transportVersion++;
		return changed;
	}

	private Set<EmployeeField> replaceHrFields(HrOwnedFields incoming) {
		Set<EmployeeField> changed = incoming.changedFieldsSince(hrFields);
		hrFields = incoming;
		return changed;
	}

	private Set<EmployeeField> changeStatus(EmployeeStatus incoming) {
		if (incoming == status) {
			return Set.of();
		}
		status = incoming;
		return Set.of(EmployeeField.STATUS);
	}

	private void recordHrEvent(HrEvent event, PayloadHash hash) {
		hrVersion = event.version();
		hrPayloadHash = hash;
		lastHrEventAt = event.occurredAt();
	}

	public TenantId tenantId() {
		return tenantId;
	}

	public EmployeeId employeeId() {
		return employeeId;
	}

	public HrOwnedFields hrFields() {
		return hrFields;
	}

	public EmployeeStatus status() {
		return status;
	}

	public long hrVersion() {
		return hrVersion;
	}

	public PayloadHash hrPayloadHash() {
		return hrPayloadHash;
	}

	public Instant lastHrEventAt() {
		return lastHrEventAt;
	}

	public TransportOwnedFields transportFields() {
		return transportFields;
	}

	public long transportVersion() {
		return transportVersion;
	}

	@Override
	public String toString() {
		return "Employee[tenantId=" + tenantId + ", employeeId=" + employeeId + ", status=" + status
				+ ", hrVersion=" + hrVersion + ", transportVersion=" + transportVersion + "]";
	}
}
