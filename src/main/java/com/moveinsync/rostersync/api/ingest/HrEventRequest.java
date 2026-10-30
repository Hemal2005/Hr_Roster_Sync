package com.moveinsync.rostersync.api.ingest;

import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One event as sent by the HR system. Only these fields are read; anything else in the
 * JSON is never mapped onto the employee.
 */
public record HrEventRequest(
		String type,
		String employeeId,
		Long version,
		Instant occurredAt,
		String firstName,
		String lastName,
		String email,
		String phone,
		String managerId,
		String officeCode,
		String shiftCode) {

	static final Set<String> FIELD_NAMES = Arrays.stream(HrEventRequest.class.getRecordComponents())
			.map(component -> component.getName())
			.collect(Collectors.toUnmodifiableSet());

	IncomingHrRecord toIncoming(HrEventType eventType) {
		return new IncomingHrRecord(eventType, employeeId, version, occurredAt, firstName, lastName, email, phone,
				managerId, officeCode, shiftCode);
	}

	@Override
	public String toString() {
		return "HrEventRequest[type=" + type + ", employeeId=" + employeeId + ", version=" + version + "]";
	}
}
