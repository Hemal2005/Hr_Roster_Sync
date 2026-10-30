package com.moveinsync.rostersync.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.rostersync.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class SchemaIT extends IntegrationTest {

	@BeforeEach
	void tenants() {
		givenTenant("acme", "BLR-01");
		givenTenant("globex", "BLR-01");
	}

	private int insertEmployee(String tenant, String employeeId, String officeCode, String status, long hrVersion) {
		return jdbc.update("""
				INSERT INTO employee (tenant_id, employee_id, first_name, email, phone, office_code, shift_code,
				                      status, hr_version, hr_payload_hash, last_hr_event_at, bucket)
				VALUES (?, ?, 'A', 'a@x.io', '+919876543210', ?, 'DAY', ?, ?, ?, ?, 0)
				""", tenant, employeeId, officeCode, status, hrVersion, new byte[] {1},
				Timestamp.from(Instant.parse("2026-10-30T08:00:00Z")));
	}

	private void logApplied(String tenant, String employeeId, long version) {
		jdbc.update("""
				INSERT INTO employee_event_log (tenant_id, source_system, employee_id, version, outcome)
				VALUES (?, 'workday', ?, ?, 'APPLIED')
				""", tenant, employeeId, version);
	}

	@Test
	void migrationCreatedTheCoreTables() {
		Integer tables = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'public'
				  AND table_name IN ('tenant', 'office', 'employee', 'employee_event_log')
				""", Integer.class);

		assertThat(tables).isEqualTo(4);
	}

	@Test
	void sameEmployeeIdCanExistInTwoTenants() {
		insertEmployee("acme", "E000042", "BLR-01", "ACTIVE", 1);
		insertEmployee("globex", "E000042", "BLR-01", "ACTIVE", 1);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE employee_id = 'E000042'", Integer.class))
				.isEqualTo(2);
	}

	@Test
	void sameEmployeeIdTwiceInOneTenantIsRefused() {
		insertEmployee("acme", "E000042", "BLR-01", "ACTIVE", 1);

		assertThatThrownBy(() -> insertEmployee("acme", "E000042", "BLR-01", "ACTIVE", 2))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void theSameAppliedChangeCannotBeLoggedTwice() {
		insertEmployee("acme", "E000042", "BLR-01", "ACTIVE", 7);
		logApplied("acme", "E000042", 7);

		assertThatThrownBy(() -> logApplied("acme", "E000042", 7)).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void unknownOfficeCodeIsRefused() {
		assertThatThrownBy(() -> insertEmployee("acme", "E000042", "CHN-99", "ACTIVE", 1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void anotherTenantsOfficeCannotBeUsed() {
		givenTenant("initech", "PUN-01");

		assertThatThrownBy(() -> insertEmployee("acme", "E000042", "PUN-01", "ACTIVE", 1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownStatusIsRefused() {
		assertThatThrownBy(() -> insertEmployee("acme", "E000042", "BLR-01", "ON_LEAVE", 1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void hrVersionMustBePositive() {
		assertThatThrownBy(() -> insertEmployee("acme", "E000042", "BLR-01", "ACTIVE", 0))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
