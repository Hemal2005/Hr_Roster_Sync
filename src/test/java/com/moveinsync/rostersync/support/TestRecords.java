package com.moveinsync.rostersync.support;

import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import java.time.Instant;

/**
 * Incoming records as the API or the nightly file would hand them to ApplyService.
 */
public final class TestRecords {

	public static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
	public static final String PHONE = "+919876543210";

	private TestRecords() {
	}

	public static IncomingHrRecord update(String employeeId, long version, String officeCode, String shiftCode) {
		return record(HrEventType.UPDATE, employeeId, version, officeCode, shiftCode, PHONE);
	}

	public static IncomingHrRecord create(String employeeId, long version, String officeCode, String shiftCode) {
		return record(HrEventType.CREATE, employeeId, version, officeCode, shiftCode, PHONE);
	}

	public static IncomingHrRecord deactivate(String employeeId, long version) {
		return new IncomingHrRecord(HrEventType.DEACTIVATE, employeeId, version, T0.plusSeconds(version),
				null, null, null, null, null, null, null);
	}

	public static IncomingHrRecord withPhone(String employeeId, long version, String phone) {
		return record(HrEventType.UPDATE, employeeId, version, "BLR-01", "DAY", phone);
	}

	private static IncomingHrRecord record(HrEventType type, String employeeId, long version, String officeCode,
			String shiftCode, String phone) {
		return new IncomingHrRecord(type, employeeId, version, T0.plusSeconds(version), "Priyanka", "Raghunathan",
				employeeId.toLowerCase() + "@acme.example", phone, "E000001", officeCode, shiftCode);
	}
}
