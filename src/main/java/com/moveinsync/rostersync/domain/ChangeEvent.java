package com.moveinsync.rostersync.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What downstream services are told about an applied change: ids, versions, the type,
 * the resulting status and the names of the fields that changed. Never field values:
 * consumers that need details fetch them through the authorised API.
 */
public record ChangeEvent(
		UUID eventId,
		TenantId tenantId,
		EmployeeId employeeId,
		ChangeEventType type,
		EmployeeStatus status,
		long hrVersion,
		long transportVersion,
		Set<EmployeeField> changedFields,
		Instant occurredAt) {

	public ChangeEvent {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(employeeId, "employeeId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(occurredAt, "occurredAt");
		changedFields = changedFields.isEmpty()
				? Set.of()
				: Set.copyOf(EnumSet.copyOf(changedFields));
	}

	public static ChangeEvent of(Employee employee, ChangeEventType type, Set<EmployeeField> changedFields,
			Instant occurredAt) {
		return new ChangeEvent(UUID.randomUUID(), employee.tenantId(), employee.employeeId(), type, employee.status(),
				employee.hrVersion(), employee.transportVersion(), changedFields, occurredAt);
	}

	/** Messages for the same employee share a key, so the broker keeps them in order. */
	public String partitionKey() {
		return tenantId.value() + ":" + employeeId.value();
	}
}
