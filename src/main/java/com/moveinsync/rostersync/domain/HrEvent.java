package com.moveinsync.rostersync.domain;

import java.time.Instant;

/**
 * A change pushed by the HR system. The permitted list is closed, so a {@code switch}
 * over an HrEvent must handle every type or it does not compile.
 */
public sealed interface HrEvent permits CreateEmployee, UpdateEmployee, DeactivateEmployee {

	EventHeader header();

	default TenantId tenantId() {
		return header().tenantId();
	}

	default String sourceSystem() {
		return header().sourceSystem();
	}

	default EmployeeId employeeId() {
		return header().employeeId();
	}

	default long version() {
		return header().version();
	}

	default Instant occurredAt() {
		return header().occurredAt();
	}
}
