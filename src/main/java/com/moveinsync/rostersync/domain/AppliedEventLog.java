package com.moveinsync.rostersync.domain;

/**
 * Record of HR changes that were applied. Recording the same
 * (tenant, source system, employee, version) twice must fail.
 */
public interface AppliedEventLog {

	void recordApplied(TenantId tenantId, String sourceSystem, EmployeeId employeeId, long version);
}
