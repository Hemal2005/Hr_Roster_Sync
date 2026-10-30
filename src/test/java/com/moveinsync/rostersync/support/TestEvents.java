package com.moveinsync.rostersync.support;

import java.util.HashMap;
import java.util.Map;

/**
 * HR events as JSON objects, the way the HR system sends them to the batch endpoint.
 */
public final class TestEvents {

	private TestEvents() {
	}

	public static Map<String, Object> update(String employeeId, long version, String officeCode, String shiftCode) {
		return event("UPDATE", employeeId, version, officeCode, shiftCode);
	}

	public static Map<String, Object> create(String employeeId, long version, String officeCode, String shiftCode) {
		return event("CREATE", employeeId, version, officeCode, shiftCode);
	}

	public static Map<String, Object> event(String type, String employeeId, long version, String officeCode,
			String shiftCode) {
		Map<String, Object> event = new HashMap<>();
		event.put("type", type);
		event.put("employeeId", employeeId);
		event.put("version", version);
		event.put("occurredAt", TestRecords.T0.plusSeconds(version).toString());
		event.put("firstName", "Priyanka");
		event.put("lastName", "Raghunathan");
		event.put("email", employeeId.toLowerCase() + "@acme.example");
		event.put("phone", TestRecords.PHONE);
		event.put("managerId", "E000001");
		event.put("officeCode", officeCode);
		event.put("shiftCode", shiftCode);
		return event;
	}

	public static Map<String, Object> with(Map<String, Object> event, String field, Object value) {
		Map<String, Object> copy = new HashMap<>(event);
		copy.put(field, value);
		return copy;
	}
}
