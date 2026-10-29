package com.moveinsync.rostersync.domain;

import static com.moveinsync.rostersync.domain.TestEvents.EMAIL;
import static com.moveinsync.rostersync.domain.TestEvents.FIRST_NAME;
import static com.moveinsync.rostersync.domain.TestEvents.LAST_NAME;
import static com.moveinsync.rostersync.domain.TestEvents.PHONE;
import static com.moveinsync.rostersync.domain.TestEvents.create;
import static com.moveinsync.rostersync.domain.TestEvents.deactivate;
import static com.moveinsync.rostersync.domain.TestEvents.hash;
import static com.moveinsync.rostersync.domain.TestEvents.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmployeeTest {

	@Test
	void createEventStartsActiveAtTheEventVersion() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));

		assertThat(employee.status()).isEqualTo(EmployeeStatus.ACTIVE);
		assertThat(employee.hrVersion()).isEqualTo(1);
		assertThat(employee.hrPayloadHash()).isEqualTo(hash(1));
		assertThat(employee.hrFields().officeCode()).isEqualTo("BLR-01");
		assertThat(employee.lastHrEventAt()).isEqualTo(Instant.parse("2026-10-30T08:00:01Z"));
	}

	@Test
	void updateForUnseenEmployeeCreatesItBecauseEventsCarryFullState() {
		Employee employee = Employee.createFrom(update(5, "BLR-01", "MORNING"), hash(5));

		assertThat(employee.hrVersion()).isEqualTo(5);
		assertThat(employee.status()).isEqualTo(EmployeeStatus.ACTIVE);
	}

	@Test
	void deactivateForUnseenEmployeeIsUnknownEmployee() {
		assertThatThrownBy(() -> Employee.createFrom(deactivate(3), hash(3)))
				.isInstanceOf(DomainException.class)
				.extracting(e -> ((DomainException) e).code())
				.isEqualTo(ErrorCode.UNKNOWN_EMPLOYEE);
	}

	@Test
	void updateReplacesHrFieldsAndReportsOnlyChangedFieldNames() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));

		var changed = employee.applyHr(update(2, "HYD-02", "MORNING"), hash(2));

		assertThat(changed).containsExactly(EmployeeField.OFFICE_CODE);
		assertThat(employee.hrFields().officeCode()).isEqualTo("HYD-02");
		assertThat(employee.hrVersion()).isEqualTo(2);
		assertThat(employee.hrPayloadHash()).isEqualTo(hash(2));
	}

	@Test
	void deactivateKeepsHrFieldsAndOnlyChangesStatus() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));

		var changed = employee.applyHr(deactivate(2), hash(2));

		assertThat(changed).containsExactly(EmployeeField.STATUS);
		assertThat(employee.status()).isEqualTo(EmployeeStatus.INACTIVE);
		assertThat(employee.hrFields().officeCode()).isEqualTo("BLR-01");
	}

	@Test
	void updateAfterDeactivateReactivatesTheEmployee() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));
		employee.applyHr(deactivate(2), hash(2));

		var changed = employee.applyHr(update(3, "BLR-01", "MORNING"), hash(3));

		assertThat(changed).containsExactly(EmployeeField.STATUS);
		assertThat(employee.status()).isEqualTo(EmployeeStatus.ACTIVE);
	}

	@Test
	void refusesAnEventThatIsNotNewerThanTheStoredVersion() {
		Employee employee = Employee.createFrom(create(7, "HYD-02", "NIGHT"), hash(7));

		assertThatThrownBy(() -> employee.applyHr(update(6, "BLR-01", "MORNING"), hash(6)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> employee.applyHr(update(7, "BLR-01", "MORNING"), hash(70)))
				.isInstanceOf(IllegalStateException.class);
		assertThat(employee.hrVersion()).isEqualTo(7);
		assertThat(employee.hrFields().officeCode()).isEqualTo("HYD-02");
	}

	@Test
	void refusesAnEventForAnotherEmployee() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));
		var other = new UpdateEmployee(
				new EventHeader(TestEvents.TENANT, TestEvents.SOURCE, new EmployeeId("E999999"), 2, TestEvents.T0),
				TestEvents.fields("BLR-01", "MORNING"));

		assertThatThrownBy(() -> employee.applyHr(other, hash(2)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void toStringContainsNoPersonalData() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));

		assertThat(employee.toString())
				.contains("E000042")
				.doesNotContain(FIRST_NAME, LAST_NAME, EMAIL, PHONE);
	}
}
