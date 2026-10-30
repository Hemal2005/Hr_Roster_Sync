package com.moveinsync.rostersync.application;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.deactivate;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static com.moveinsync.rostersync.support.TestRecords.withPhone;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.TransportOwnedFields;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.support.IntegrationTest;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApplyServiceIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final EmployeeId E42 = new EmployeeId("E000042");

	@Autowired
	ApplyService applyService;

	@Autowired
	EmployeeRepository employees;

	@BeforeEach
	void tenant() {
		givenTenant("acme", "BLR-01", "HYD-02");
	}

	private ApplyResult apply(IncomingHrRecord record) {
		return applyService.apply(ACME, "workday", record);
	}

	private Employee stored() {
		return employees.find(ACME, E42).orElseThrow();
	}

	private int appliedLogRows() {
		return jdbc.queryForObject("SELECT count(*) FROM employee_event_log", Integer.class);
	}

	private Timestamp updatedAt() {
		return jdbc.queryForObject("SELECT updated_at FROM employee WHERE employee_id = ?", Timestamp.class,
				E42.value());
	}

	@Test
	void firstEventCreatesTheEmployeeAndRecordsTheAppliedChange() {
		assertThat(apply(create("E000042", 1, "BLR-01", "DAY"))).isEqualTo(ApplyResult.of(ApplyOutcome.APPLIED));

		assertThat(stored().hrVersion()).isEqualTo(1);
		assertThat(appliedLogRows()).isEqualTo(1);
	}

	@Test
	void newerVersionUpdatesTheEmployee() {
		apply(create("E000042", 1, "BLR-01", "DAY"));

		assertThat(apply(update("E000042", 2, "HYD-02", "NIGHT")).outcome()).isEqualTo(ApplyOutcome.APPLIED);
		assertThat(stored().hrFields().officeCode()).isEqualTo("HYD-02");
		assertThat(appliedLogRows()).isEqualTo(2);
	}

	@Test
	void staleIgnored() {
		apply(update("E000042", 5, "HYD-02", "NIGHT"));

		assertThat(apply(update("E000042", 4, "BLR-01", "DAY")).outcome()).isEqualTo(ApplyOutcome.STALE);
		assertThat(stored().hrVersion()).isEqualTo(5);
		assertThat(stored().hrFields().officeCode()).isEqualTo("HYD-02");
		assertThat(appliedLogRows()).isEqualTo(1);
	}

	@Test
	void duplicateIsNoOp() {
		apply(update("E000042", 3, "BLR-01", "DAY"));
		Timestamp before = updatedAt();

		assertThat(apply(update("E000042", 3, "BLR-01", "DAY")).outcome()).isEqualTo(ApplyOutcome.DUPLICATE);
		assertThat(updatedAt()).isEqualTo(before);
		assertThat(appliedLogRows()).isEqualTo(1);
	}

	@Test
	void sameVersionWithDifferentContentIsRejectedAsConflict() {
		apply(update("E000042", 3, "BLR-01", "DAY"));

		assertThat(apply(update("E000042", 3, "HYD-02", "DAY"))).isEqualTo(ApplyResult.rejected(ErrorCode.VERSION_CONFLICT));
		assertThat(stored().hrFields().officeCode()).isEqualTo("BLR-01");
	}

	@Test
	void invalidRecordIsRejectedAndWritesNothing() {
		assertThat(apply(withPhone("E000042", 1, "12ab"))).isEqualTo(ApplyResult.rejected(ErrorCode.INVALID_PHONE));

		assertThat(employees.find(ACME, E42)).isEmpty();
		assertThat(appliedLogRows()).isZero();
	}

	@Test
	void unknownOfficeIsRejected() {
		assertThat(apply(update("E000042", 1, "CHN-99", "DAY"))).isEqualTo(ApplyResult.rejected(ErrorCode.UNKNOWN_OFFICE));
	}

	@Test
	void deactivationOfAnUnknownEmployeeIsRejected() {
		assertThat(apply(deactivate("E000042", 1))).isEqualTo(ApplyResult.rejected(ErrorCode.UNKNOWN_EMPLOYEE));
		assertThat(appliedLogRows()).isZero();
	}

	@Test
	void deactivationMarksTheEmployeeInactive() {
		apply(create("E000042", 1, "BLR-01", "DAY"));

		assertThat(apply(deactivate("E000042", 2)).outcome()).isEqualTo(ApplyOutcome.APPLIED);
		assertThat(stored().status()).isEqualTo(EmployeeStatus.INACTIVE);
	}

	@Test
	void hrCannotOverwriteTransportFields() {
		apply(create("E000042", 1, "BLR-01", "DAY"));
		Employee employee = stored();
		employee.applyTransport(new TransportOwnedFields("Gate 2, Tech Park", "wheelchair"), 0);
		employees.updateTransportFields(ACME, employee, 0);

		apply(update("E000042", 2, "HYD-02", "NIGHT"));

		assertThat(stored().transportFields().pickupPoint()).isEqualTo("Gate 2, Tech Park");
		assertThat(stored().transportFields().specialAssistance()).isEqualTo("wheelchair");
		assertThat(stored().hrVersion()).isEqualTo(2);
	}

	@Test
	void sameEmployeeIdInAnotherTenantIsASeparateEmployee() {
		givenTenant("globex", "BLR-01");
		apply(update("E000042", 9, "HYD-02", "NIGHT"));

		ApplyResult globex = applyService.apply(new TenantId("globex"), "workday", update("E000042", 1, "BLR-01", "DAY"));

		assertThat(globex.outcome()).isEqualTo(ApplyOutcome.APPLIED);
		assertThat(stored().hrVersion()).isEqualTo(9);
	}
}
