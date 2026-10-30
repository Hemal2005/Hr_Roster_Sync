package com.moveinsync.rostersync.domain;

import java.util.Optional;

public enum ChangeEventType {
	EMPLOYEE_CREATED,
	EMPLOYEE_UPDATED,
	EMPLOYEE_DEACTIVATED,
	EMPLOYEE_REACTIVATED,
	TRANSPORT_UPDATED;

	/** The event for an applied HR change, from the status before (empty if new) and after. */
	public static ChangeEventType forHrChange(Optional<EmployeeStatus> before, EmployeeStatus after) {
		if (before.isEmpty()) {
			return EMPLOYEE_CREATED;
		}
		if (before.get() == EmployeeStatus.ACTIVE && after == EmployeeStatus.INACTIVE) {
			return EMPLOYEE_DEACTIVATED;
		}
		if (before.get() == EmployeeStatus.INACTIVE && after == EmployeeStatus.ACTIVE) {
			return EMPLOYEE_REACTIVATED;
		}
		return EMPLOYEE_UPDATED;
	}
}
