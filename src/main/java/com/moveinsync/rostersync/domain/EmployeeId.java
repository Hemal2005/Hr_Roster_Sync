package com.moveinsync.rostersync.domain;

public record EmployeeId(String value) {

	public EmployeeId {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("employeeId must not be blank");
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
