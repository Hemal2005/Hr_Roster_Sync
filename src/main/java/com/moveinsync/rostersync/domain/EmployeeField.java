package com.moveinsync.rostersync.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every employee attribute, the name it has on the wire, and the single party allowed
 * to write it. Phone, shift and status are HR-owned by assumption: the case study names
 * only name, email, manager and office, but those three also come from the HR system.
 */
public enum EmployeeField {
	FIRST_NAME("firstName", FieldOwner.HR),
	LAST_NAME("lastName", FieldOwner.HR),
	EMAIL("email", FieldOwner.HR),
	PHONE("phone", FieldOwner.HR),
	MANAGER_ID("managerId", FieldOwner.HR),
	OFFICE_CODE("officeCode", FieldOwner.HR),
	SHIFT_CODE("shiftCode", FieldOwner.HR),
	STATUS("status", FieldOwner.HR),
	PICKUP_POINT("pickupPoint", FieldOwner.TRANSPORT),
	SPECIAL_ASSISTANCE("specialAssistance", FieldOwner.TRANSPORT);

	private static final Map<String, EmployeeField> BY_WIRE_NAME = Arrays.stream(values())
			.collect(Collectors.toUnmodifiableMap(EmployeeField::wireName, Function.identity()));

	private final String wireName;
	private final FieldOwner owner;

	EmployeeField(String wireName, FieldOwner owner) {
		this.wireName = wireName;
		this.owner = owner;
	}

	public String wireName() {
		return wireName;
	}

	public FieldOwner owner() {
		return owner;
	}

	public static Optional<EmployeeField> fromWireName(String name) {
		return Optional.ofNullable(BY_WIRE_NAME.get(name));
	}
}
