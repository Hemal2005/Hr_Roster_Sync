package com.moveinsync.rostersync.api.employee;

import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeStatus;

/**
 * An employee as returned to an authorised caller. It holds personal data, so its
 * toString lists ids and versions only.
 */
public record EmployeeView(
		String employeeId,
		EmployeeStatus status,
		String firstName,
		String lastName,
		String email,
		String phone,
		String managerId,
		String officeCode,
		String shiftCode,
		long hrVersion,
		String pickupPoint,
		String specialAssistance,
		long transportVersion) {

	static EmployeeView of(Employee employee) {
		var hr = employee.hrFields();
		var transport = employee.transportFields();
		return new EmployeeView(employee.employeeId().value(), employee.status(), hr.firstName(), hr.lastName(),
				hr.email(), hr.phone(), hr.managerId(), hr.officeCode(), hr.shiftCode(), employee.hrVersion(),
				transport.pickupPoint(), transport.specialAssistance(), employee.transportVersion());
	}

	@Override
	public String toString() {
		return "EmployeeView[employeeId=" + employeeId + ", hrVersion=" + hrVersion + ", transportVersion="
				+ transportVersion + "]";
	}
}
