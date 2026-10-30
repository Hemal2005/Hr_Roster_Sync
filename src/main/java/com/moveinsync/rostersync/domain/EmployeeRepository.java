package com.moveinsync.rostersync.domain;

import java.util.Optional;

/**
 * Storage for employees. Every method takes the tenant explicitly, so no query can be
 * written that forgets it; methods that also receive an {@link Employee} refuse one
 * belonging to a different tenant.
 */
public interface EmployeeRepository {

	Optional<Employee> find(TenantId tenantId, EmployeeId employeeId);

	/**
	 * Reads the row and locks it until the caller's transaction ends, so concurrent
	 * events for the same employee are decided one after another. Requires an active
	 * transaction.
	 */
	Optional<Employee> findForUpdate(TenantId tenantId, EmployeeId employeeId);

	/** Inserts a new employee with its HR fields; transport fields start empty. */
	void insert(TenantId tenantId, Employee employee);

	/**
	 * Writes the HR-owned columns only, and only if the stored HR version is lower than
	 * the employee's. Returns false when a newer or equal version is already stored.
	 */
	boolean updateHrFields(TenantId tenantId, Employee employee);

	/**
	 * Writes the transport-owned columns only, and only if the stored transport version
	 * still equals {@code expectedVersion}. Returns false otherwise.
	 */
	boolean updateTransportFields(TenantId tenantId, Employee employee, long expectedVersion);

	/**
	 * Marks the employee INACTIVE at the same HR version, only if the stored row is still
	 * ACTIVE at that version. Returns false if it moved on.
	 */
	boolean markInactiveAtSameVersion(TenantId tenantId, Employee employee);

	long countActive(TenantId tenantId);
}
