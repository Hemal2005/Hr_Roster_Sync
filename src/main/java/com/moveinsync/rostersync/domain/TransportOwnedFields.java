package com.moveinsync.rostersync.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * Attributes only the transport admin may write. Both are personal data: a pickup
 * point is effectively a home location and assistance needs can be health information.
 */
public final class TransportOwnedFields extends OwnedFieldGroup {

	public static final TransportOwnedFields NONE = new TransportOwnedFields(null, null);

	public TransportOwnedFields(String pickupPoint, String specialAssistance) {
		super(FieldOwner.TRANSPORT, values(pickupPoint, specialAssistance));
	}

	private static Map<EmployeeField, String> values(String pickupPoint, String specialAssistance) {
		Map<EmployeeField, String> values = new EnumMap<>(EmployeeField.class);
		values.put(EmployeeField.PICKUP_POINT, pickupPoint);
		values.put(EmployeeField.SPECIAL_ASSISTANCE, specialAssistance);
		return values;
	}

	public String pickupPoint() {
		return value(EmployeeField.PICKUP_POINT);
	}

	public String specialAssistance() {
		return value(EmployeeField.SPECIAL_ASSISTANCE);
	}
}
