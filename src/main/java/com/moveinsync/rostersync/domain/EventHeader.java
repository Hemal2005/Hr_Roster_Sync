package com.moveinsync.rostersync.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What every HR event carries regardless of its type. {@code version} is the source
 * system's per-employee counter; {@code occurredAt} is when HR made the change.
 */
public record EventHeader(TenantId tenantId, String sourceSystem, EmployeeId employeeId,
		long version, Instant occurredAt) {

	public EventHeader {
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(employeeId, "employeeId");
		Objects.requireNonNull(occurredAt, "occurredAt");
		if (sourceSystem == null || sourceSystem.isBlank()) {
			throw new IllegalArgumentException("sourceSystem must not be blank");
		}
		if (version < 1) {
			throw new IllegalArgumentException("version must be >= 1");
		}
	}
}
