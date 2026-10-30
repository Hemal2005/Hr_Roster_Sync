package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import org.springframework.stereotype.Service;

/**
 * Reads and transport-admin edits of employees, always inside one tenant. An employee of
 * another tenant is reported exactly like one that does not exist.
 */
@Service
public class EmployeeService {

	private final EmployeeRepository employees;

	EmployeeService(EmployeeRepository employees) {
		this.employees = employees;
	}

	public Employee get(TenantId tenantId, EmployeeId employeeId) {
		return employees.find(tenantId, employeeId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
	}
}
