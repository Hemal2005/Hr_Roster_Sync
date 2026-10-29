package com.moveinsync.rostersync.domain;

/**
 * Every employee attribute and the single party allowed to write it.
 */
public enum EmployeeField {
	FIRST_NAME(FieldOwner.HR),
	LAST_NAME(FieldOwner.HR),
	EMAIL(FieldOwner.HR),
	PHONE(FieldOwner.HR),
	MANAGER_ID(FieldOwner.HR),
	OFFICE_CODE(FieldOwner.HR),
	SHIFT_CODE(FieldOwner.HR),
	STATUS(FieldOwner.HR);

	private final FieldOwner owner;

	EmployeeField(FieldOwner owner) {
		this.owner = owner;
	}

	public FieldOwner owner() {
		return owner;
	}
}
