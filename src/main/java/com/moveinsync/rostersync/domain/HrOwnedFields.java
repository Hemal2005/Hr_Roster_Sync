package com.moveinsync.rostersync.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * The full HR-owned state of an employee, excluding status (which follows from the
 * event type). Events carry this whole group, never a partial delta.
 */
public final class HrOwnedFields extends OwnedFieldGroup {

	public HrOwnedFields(String firstName, String lastName, String email, String phone,
			String managerId, String officeCode, String shiftCode) {
		super(FieldOwner.HR, values(firstName, lastName, email, phone, managerId, officeCode, shiftCode));
	}

	private static Map<EmployeeField, String> values(String firstName, String lastName, String email,
			String phone, String managerId, String officeCode, String shiftCode) {
		Map<EmployeeField, String> values = new EnumMap<>(EmployeeField.class);
		values.put(EmployeeField.FIRST_NAME, firstName);
		values.put(EmployeeField.LAST_NAME, lastName);
		values.put(EmployeeField.EMAIL, email);
		values.put(EmployeeField.PHONE, phone);
		values.put(EmployeeField.MANAGER_ID, managerId);
		values.put(EmployeeField.OFFICE_CODE, officeCode);
		values.put(EmployeeField.SHIFT_CODE, shiftCode);
		return values;
	}

	public String firstName() {
		return value(EmployeeField.FIRST_NAME);
	}

	public String lastName() {
		return value(EmployeeField.LAST_NAME);
	}

	public String email() {
		return value(EmployeeField.EMAIL);
	}

	public String phone() {
		return value(EmployeeField.PHONE);
	}

	public String managerId() {
		return value(EmployeeField.MANAGER_ID);
	}

	public String officeCode() {
		return value(EmployeeField.OFFICE_CODE);
	}

	public String shiftCode() {
		return value(EmployeeField.SHIFT_CODE);
	}
}
