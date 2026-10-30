package com.moveinsync.rostersync.application.reconciliation;

import static com.moveinsync.rostersync.support.ExportFiles.active;
import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ExportFiles;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.TestRecords;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final Instant AS_OF = TestRecords.T0.plusSeconds(3600);

	@Autowired
	ApplyService applyService;

	@Autowired
	ReconciliationService service;

	@Autowired
	ReconciliationStore store;

	@Autowired
	MeterRegistry meters;

	@TempDir
	Path dir;

	@BeforeEach
	void tenActiveEmployees() {
		givenTenant("acme", "BLR-01", "HYD-02");
		for (int i = 1; i <= 10; i++) {
			applyService.apply(ACME, "workday", create(id(i), 1, "BLR-01", "DAY"));
		}
	}

	private static String id(int i) {
		return "E%02d".formatted(i);
	}

	/** Export rows for employees 1..n unchanged, plus extra rows. */
	private static List<String> unchanged(int n, String... extra) {
		List<String> rows = new ArrayList<>();
		for (int i = 1; i <= n; i++) {
			rows.add(active(id(i), 1, "BLR-01", "DAY"));
		}
		rows.addAll(List.of(extra));
		return rows;
	}

	private record Run(UUID id, RunStatus status) {
	}

	private Run run(List<String> rows, boolean withTrailer) {
		UUID runId = UUID.randomUUID();
		store.createRun(runId, ACME, "export.csv", AS_OF, "workday");
		Path export = ExportFiles.write(dir, "acme", AS_OF, rows, withTrailer);
		return new Run(runId, service.execute(runId, ACME, AS_OF, export));
	}

	private ReconciliationRun stored(Run run) {
		return store.find(ACME, run.id()).orElseThrow();
	}

	private Map<String, Object> employee(String id) {
		return jdbc.queryForMap("SELECT status, hr_version, shift_code FROM employee WHERE employee_id = ?", id);
	}

	private long activeCount() {
		return jdbc.queryForObject("SELECT count(*) FROM employee WHERE status = 'ACTIVE'", Long.class);
	}

	private double brakeTrips(String reason) {
		var counter = meters.find("reconciliation.brake.tripped").tags("tenant", "acme", "reason", reason).counter();
		return counter == null ? 0 : counter.count();
	}

	@Test
	void normalNightAppliesThePlan() {
		List<String> rows = unchanged(9, active("E11", 1, "BLR-01", "DAY"));
		rows.set(1, active("E02", 2, "BLR-01", "NIGHT"));

		Run run = run(rows, true);

		assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
		assertThat(employee("E02")).containsEntry("shift_code", "NIGHT").containsEntry("hr_version", 2L);
		assertThat(employee("E10")).containsEntry("status", "INACTIVE").containsEntry("hr_version", 1L);
		assertThat(employee("E11")).containsEntry("status", "ACTIVE");
		ReconciliationRun stored = stored(run);
		assertThat(stored.brakeTripped()).isFalse();
		assertThat(stored.missingRatio()).isEqualByComparingTo(new BigDecimal("0.1"));
		assertThat(stored.applied()).isEqualTo(3);
		assertThat(stored.completedAt()).isNotNull();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM outbox WHERE event_type = 'EMPLOYEE_DEACTIVATED' AND aggregate_id = 'E10'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void brakeStopsRun() {
		double before = brakeTrips("MISSING_RATIO_EXCEEDED");
		List<String> rows = unchanged(6);
		rows.set(1, active("E02", 2, "BLR-01", "NIGHT"));

		Run run = run(rows, true);

		assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
		ReconciliationRun stored = stored(run);
		assertThat(stored.brakeTripped()).isTrue();
		assertThat(stored.brakeReason()).isEqualTo("MISSING_RATIO_EXCEEDED");
		assertThat(stored.toDeactivate()).isEqualTo(4);
		assertThat(stored.activeBefore()).isEqualTo(10);
		assertThat(stored.missingRatio()).isEqualByComparingTo(new BigDecimal("0.4"));
		// Held as a whole: not even the update is applied.
		assertThat(activeCount()).isEqualTo(10);
		assertThat(employee("E02")).containsEntry("shift_code", "DAY").containsEntry("hr_version", 1L);
		assertThat(brakeTrips("MISSING_RATIO_EXCEEDED")).isEqualTo(before + 1);
	}

	@Test
	void fileWithoutTrailerTripsEvenWhenNobodyIsMissing() {
		Run run = run(unchanged(10), false);

		assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
		assertThat(stored(run).brakeReason()).isEqualTo("FILE_TRUNCATED");
		assertThat(stored(run).trailerOk()).isFalse();
	}

	@Test
	void approvalLaterStillRespectsLiveChangesMadeInBetween() {
		List<String> rows = unchanged(6);
		rows.set(1, active("E02", 2, "BLR-01", "NIGHT"));
		Run run = run(rows, true);
		assertThat(run.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
		// Live HR events after the plan was made: E02 moves to v3, E07 (planned to be
		// deactivated as missing) gets a newer version.
		applyService.apply(ACME, "workday", update("E02", 3, "HYD-02", "DAY"));
		applyService.apply(ACME, "workday", update("E07", 2, "BLR-01", "DAY"));

		assertThat(store.recordDecision(run.id(), RunStatus.APPLYING, "operator-1")).isTrue();
		RunStatus status = service.applyPlan(run.id(), ACME, AS_OF);

		assertThat(status).isEqualTo(RunStatus.COMPLETED);
		assertThat(employee("E02")).containsEntry("hr_version", 3L).containsEntry("shift_code", "DAY");
		assertThat(employee("E07")).containsEntry("status", "ACTIVE");
		assertThat(List.of("E08", "E09", "E10")).allSatisfy(e -> assertThat(employee(e)).containsEntry("status", "INACTIVE"));
		assertThat(stored(run).applied()).isEqualTo(3);
		assertThat(stored(run).skipped()).isEqualTo(2);
		assertThat(stored(run).decidedBy()).isEqualTo("operator-1");
	}

	@Test
	void unreadableFileFailsTheRunAndChangesNothing() {
		UUID runId = UUID.randomUUID();
		store.createRun(runId, ACME, "broken.csv", AS_OF, "workday");
		Path broken = ExportFiles.writeLines(dir, List.of("not an export"));

		RunStatus status = service.execute(runId, ACME, AS_OF, broken);

		assertThat(status).isEqualTo(RunStatus.FAILED);
		assertThat(store.find(ACME, runId).orElseThrow().failureCode()).isEqualTo("DomainException");
		assertThat(activeCount()).isEqualTo(10);
	}
}
