package com.moveinsync.rostersync.domain;

import java.time.Instant;

/**
 * Builders for domain tests. The personal values are distinctive so that tests can
 * assert they never leak into toString or messages.
 */
public final class TestEvents {

	public static final TenantId TENANT = new TenantId("acme");
	public static final EmployeeId EMPLOYEE = new EmployeeId("E000042");
	public static final String SOURCE = "workday";
	public static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

	public static final String FIRST_NAME = "Priyanka";
	public static final String LAST_NAME = "Raghunathan";
	public static final String EMAIL = "priyanka.r@acme.example";
	public static final String PHONE = "+919876543210";

	private TestEvents() {
	}

	public static HrOwnedFields fields(String officeCode, String shiftCode) {
		return new HrOwnedFields(FIRST_NAME, LAST_NAME, EMAIL, PHONE, "E000001", officeCode, shiftCode);
	}

	public static EventHeader header(long version) {
		return new EventHeader(TENANT, SOURCE, EMPLOYEE, version, T0.plusSeconds(version));
	}

	public static CreateEmployee create(long version, String officeCode, String shiftCode) {
		return new CreateEmployee(header(version), fields(officeCode, shiftCode));
	}

	public static UpdateEmployee update(long version, String officeCode, String shiftCode) {
		return new UpdateEmployee(header(version), fields(officeCode, shiftCode));
	}

	public static DeactivateEmployee deactivate(long version) {
		return new DeactivateEmployee(header(version));
	}

	public static PayloadHash hash(int seed) {
		return new PayloadHash(new byte[] {(byte) seed, 1, 2, 3});
	}
}
