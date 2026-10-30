package com.moveinsync.rostersync.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key of {@link EmployeeEntity}: (tenant_id, employee_id).
 */
public class EmployeeKey implements Serializable {

	private String tenantId;
	private String employeeId;

	protected EmployeeKey() {
	}

	public EmployeeKey(String tenantId, String employeeId) {
		this.tenantId = tenantId;
		this.employeeId = employeeId;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof EmployeeKey key
				&& Objects.equals(key.tenantId, tenantId)
				&& Objects.equals(key.employeeId, employeeId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(tenantId, employeeId);
	}
}
