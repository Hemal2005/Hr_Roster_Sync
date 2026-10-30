package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.ApplyOutcome;
import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.ReconciliationSource;
import com.moveinsync.rostersync.application.reconciliation.ExportReader;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationRun;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationService;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore;
import com.moveinsync.rostersync.application.reconciliation.RunStatus;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.PayloadHasher;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.tools.ExportGenerator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
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

	@Autowired
	ReconciliationService reconciliation;

	@Autowired
	ReconciliationStore reconciliationStore;

	@Autowired
	BucketAssigner buckets;

	@Autowired
	DataSource dataSource;

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

	/**
	 * 200,000 active employees; the nightly file stops after 120,000 rows, mid-row, with no
	 * trailer. Nobody may be deactivated until an operator decides, and rejecting the run
	 * leaves everyone active.
	 */
	@Test
	void truncatedFileAt120kDeactivatesNobody(@TempDir Path dir) throws Exception {
		givenTenant("acme", ExportGenerator.DEFAULT_OFFICES.toArray(String[]::new));
		Path full = ExportGenerator.write(dir.resolve("full.csv"), "acme", ExportGenerator.DEFAULT_AS_OF, 200_000, null,
				ExportGenerator.DEFAULT_OFFICES);
		seedEmployeesFrom(full, dir.resolve("seed.csv"));
		assertThat(activeCount()).isEqualTo(200_000);

		Path cut = ExportGenerator.write(dir.resolve("cut.csv"), "acme", ExportGenerator.DEFAULT_AS_OF, 200_000, 120_000,
				ExportGenerator.DEFAULT_OFFICES);
		UUID runId = UUID.randomUUID();
		reconciliationStore.createRun(runId, ACME, "cut.csv", ExportGenerator.DEFAULT_AS_OF, "workday");
		RunStatus status = reconciliation.execute(runId, ACME, ExportGenerator.DEFAULT_AS_OF, cut);

		ReconciliationRun run = reconciliationStore.find(ACME, runId).orElseThrow();
		assertThat(status).isEqualTo(RunStatus.AWAITING_APPROVAL);
		assertThat(run.brakeReason()).isEqualTo("FILE_TRUNCATED");
		assertThat(run.trailerOk()).isFalse();
		// E120001 is the half row: malformed, but counted as present.
		assertThat(run.rowsInvalid()).isEqualTo(1);
		assertThat(run.toDeactivate()).isEqualTo(79_999);
		assertThat(run.toCreate()).isZero();
		assertThat(run.toUpdate()).isZero();
		assertThat(run.conflicts()).isZero();
		assertThat(activeCount()).isEqualTo(200_000);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();

		assertThat(reconciliationStore.recordDecision(runId, RunStatus.REJECTED, "operator-1")).isTrue();
		assertThat(activeCount()).isEqualTo(200_000);
	}

	/**
	 * Loads every row of {@code export} as an active employee at version 1 with COPY, hashed
	 * the way reconciliation hashes it, so the matching export rows are unchanged.
	 */
	private void seedEmployeesFrom(Path export, Path seed) throws Exception {
		PayloadHasher hasher = new PayloadHasher();
		String lastEvent = ExportGenerator.DEFAULT_AS_OF.minusSeconds(86_400).toString();
		try (BufferedWriter out = Files.newBufferedWriter(seed, StandardCharsets.UTF_8)) {
			ExportReader.read(export, new ExportReader.Handler() {
				@Override
				public void row(long lineNo, IncomingHrRecord record) {
					String hash = hasher.hash(record.toEvent(ACME, ReconciliationSource.NAME)).hex();
					String line = String.join(",", "acme", record.employeeId(), record.firstName(), record.lastName(),
							record.email(), record.phone(), record.managerId(), record.officeCode(), record.shiftCode(),
							"ACTIVE", "1", "\\x" + hash, lastEvent,
							Integer.toString(buckets.bucketOf(new EmployeeId(record.employeeId()))));
					try {
						out.write(line);
						out.write('\n');
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}

				@Override
				public void malformed(long lineNo, String employeeId, ErrorCode code) {
					throw new AssertionError("generated row is malformed at line " + lineNo + ": " + code);
				}
			});
		}
		try (Connection connection = dataSource.getConnection(); Reader in = Files.newBufferedReader(seed)) {
			connection.unwrap(PGConnection.class).getCopyAPI().copyIn("""
					COPY employee (tenant_id, employee_id, first_name, last_name, email, phone, manager_id,
					               office_code, shift_code, status, hr_version, hr_payload_hash, last_hr_event_at, bucket)
					FROM STDIN WITH (FORMAT csv)
					""", in);
		}
	}

	private long activeCount() {
		return jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = 'acme' AND status = 'ACTIVE'",
				Long.class);
	}
}
