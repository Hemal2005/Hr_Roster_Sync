package com.moveinsync.rostersync.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The employee aggregate. State changes only through {@link #applyHr}; there are no
 * setters. Deciding whether an event should be applied at all (stale, duplicate,
 * conflict) is the caller's job via the version policy; this class guards the
 * invariant that the HR version only moves forward.
 */
public final class Employee {

	private final TenantId tenantId;
	private final EmployeeId employeeId;
	private HrOwnedFields hrFields;
	private EmployeeStatus status;
	private long hrVersion;
	private PayloadHash hrPayloadHash;
	private Instant lastHrEventAt;

	private Employee(TenantId tenantId, EmployeeId employeeId) {
		this.tenantId = tenantId;
		this.employeeId = employeeId;
	}

	/**
	 * Builds a new employee from the first event seen for it. An update is accepted
	 * because events carry the full HR state, so a lost create costs nothing.
	 */
	public static Employee createFrom(HrEvent event, PayloadHash hash) {
		Objects.requireNonNull(hash, "hash");
		HrOwnedFields fields = switch (event) {
			case CreateEmployee create -> create.fields();
			case UpdateEmployee update -> update.fields();
			case DeactivateEmployee deactivate -> throw new DomainException(ErrorCode.UNKNOWN_EMPLOYEE);
		};
		Employee employee = new Employee(event.tenantId(), event.employeeId());
		employee.hrFields = fields;
		employee.status = EmployeeStatus.ACTIVE;
		employee.recordHrEvent(event, hash);
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
			case DeactivateEmployee deactivate -> changed.addAll(changeStatus(EmployeeStatus.INACTIVE));
		}
		recordHrEvent(event, hash);
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

	@Override
	public String toString() {
		return "Employee[tenantId=" + tenantId + ", employeeId=" + employeeId + ", status=" + status
				+ ", hrVersion=" + hrVersion + "]";
	}
}
