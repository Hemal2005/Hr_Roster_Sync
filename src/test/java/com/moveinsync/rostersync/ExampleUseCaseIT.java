package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.ApplyOutcome;
import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The worked examples from the case study, end to end against the database.
 */
class ExampleUseCaseIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");

	@Autowired
	ApplyService applyService;

	@Autowired
	EmployeeRepository employees;

	@Test
	void transferV7BeforeShiftV6() {
		givenTenant("acme", "BLR-01", "HYD-02");
		applyService.apply(ACME, "workday", create("E000042", 5, "BLR-01", "MORNING"));
		// HR made v6 (shift -> NIGHT), then v7 (office -> HYD-02, still NIGHT). v7 arrives first.
		var v6ShiftChange = update("E000042", 6, "BLR-01", "NIGHT");
		var v7Transfer = update("E000042", 7, "HYD-02", "NIGHT");

		var v7 = applyService.apply(ACME, "workday", v7Transfer);
		var v6 = applyService.apply(ACME, "workday", v6ShiftChange);

		assertThat(v7.outcome()).isEqualTo(ApplyOutcome.APPLIED);
		assertThat(v6.outcome()).isEqualTo(ApplyOutcome.STALE);
		Employee employee = employees.find(ACME, new EmployeeId("E000042")).orElseThrow();
		assertThat(employee.hrVersion()).isEqualTo(7);
		assertThat(employee.hrFields().officeCode()).isEqualTo("HYD-02");
		assertThat(employee.hrFields().shiftCode()).isEqualTo("NIGHT");
	}
}
