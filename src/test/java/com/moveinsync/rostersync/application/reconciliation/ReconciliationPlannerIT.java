package com.moveinsync.rostersync.application.reconciliation;

import static com.moveinsync.rostersync.support.ExportFiles.active;
import static com.moveinsync.rostersync.support.TestRecords.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore.RunError;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore.StoredItem;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction;
import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.support.ExportFiles;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.TestRecords;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationPlannerIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final Instant AS_OF = TestRecords.T0.plusSeconds(3600);

	@Autowired
	ApplyService applyService;

	@Autowired
	ReconciliationPlanner planner;

	@Autowired
	ReconciliationStore store;

	@TempDir
	Path dir;

	@BeforeEach
	void storedEmployees() {
		givenTenant("acme", "BLR-01", "HYD-02");
		for (String id : List.of("E1", "E2", "E3", "E4")) {
			applyService.apply(ACME, "workday", create(id, id.equals("E4") ? 5 : 1, "BLR-01", "DAY"));
		}
		// Changed by a live event after the export was taken.
		applyService.apply(ACME, "workday", new IncomingHrRecord(HrEventType.CREATE, "E5", 1L, AS_OF.plusSeconds(300),
				"Priyanka", "Raghunathan", "e5@acme.example", TestRecords.PHONE, "E000001", "BLR-01", "DAY"));
	}

	private UUID newRun() {
		UUID runId = UUID.randomUUID();
		store.createRun(runId, ACME, "export.csv", AS_OF, "workday");
		return runId;
	}

	@Test
	void plansOnlyTheChangesAndRecordsBadLinesByNumberAndCode() {
		Path export = ExportFiles.write(dir, "acme", AS_OF, List.of(
				active("E1", 1, "BLR-01", "DAY"),           // line 3: unchanged
				active("E2", 2, "BLR-01", "NIGHT"),         // line 4: newer, different -> UPDATE
				active("E4", 4, "HYD-02", "DAY"),           // line 5: older than stored v5 -> nothing
				active("E6", 1, "BLR-01", "DAY"),           // line 6: new joiner -> CREATE
				active("E7", 1, "CHN-99", "DAY"),           // line 7: unknown office -> error, not created
				active("E6", 1, "BLR-01", "DAY")            // line 8: duplicate row -> error
		), true);                                        // E3 absent -> DEACTIVATE; E5 absent but changed later -> nothing
		UUID runId = newRun();

		PlanSummary summary = planner.plan(runId, ACME, AS_OF, export);

		List<StoredItem> items = store.items(runId, 0, 100);
		assertThat(items).extracting(StoredItem::action, StoredItem::employeeId, StoredItem::rowVersion)
				.containsExactlyInAnyOrder(
						tuple(ReconciliationAction.UPDATE, "E2", 2L),
						tuple(ReconciliationAction.DEACTIVATE, "E3", 1L),
						tuple(ReconciliationAction.CREATE, "E6", 1L));
		assertThat(items).filteredOn(i -> i.action() == ReconciliationAction.DEACTIVATE)
				.allMatch(i -> i.payload() == null);
		assertThat(items).filteredOn(i -> i.employeeId().equals("E2")).singleElement()
				.satisfies(i -> assertThat(i.payload()).contains("\"shiftCode\": \"NIGHT\""));

		assertThat(store.errors(ACME, runId, 0, 100))
				.containsExactly(new RunError(7, "E7", ErrorCode.UNKNOWN_OFFICE),
						new RunError(8, "E6", ErrorCode.DUPLICATE_IN_EXPORT));

		assertThat(summary).isEqualTo(new PlanSummary(6, 2, 1, 1, 1, 0, 5, 6L, true));
	}

	@Test
	void invalidRowNeverDeactivatesItsEmployee() {
		Path export = ExportFiles.write(dir, "acme", AS_OF, List.of(
				active("E1", 1, "BLR-01", "DAY"), active("E2", 1, "CHN-99", "DAY"),
				active("E3", 1, "BLR-01", "DAY"), active("E4", 5, "BLR-01", "DAY")), true);
		UUID runId = newRun();

		PlanSummary summary = planner.plan(runId, ACME, AS_OF, export);

		assertThat(summary.toDeactivate()).isZero();
		assertThat(store.items(runId, 0, 100)).isEmpty();
	}

	@Test
	void sameVersionDifferentContentIsReportedAsAConflict() {
		Path export = ExportFiles.write(dir, "acme", AS_OF, List.of(
				active("E1", 1, "HYD-02", "DAY"), active("E2", 1, "BLR-01", "DAY"),
				active("E3", 1, "BLR-01", "DAY"), active("E4", 5, "BLR-01", "DAY")), true);
		UUID runId = newRun();

		PlanSummary summary = planner.plan(runId, ACME, AS_OF, export);

		assertThat(summary.conflicts()).isEqualTo(1);
		assertThat(store.errors(ACME, runId, 0, 100)).containsExactly(new RunError(3, "E1", ErrorCode.VERSION_CONFLICT));
		assertThat(store.items(runId, 0, 100)).isEmpty();
	}

	@Test
	void workingFilesAreDeletedAfterPlanning() throws IOException {
		Path export = ExportFiles.write(dir, "acme", AS_OF, List.of(active("E1", 1, "BLR-01", "DAY")), true);
		UUID runId = newRun();

		planner.plan(runId, ACME, AS_OF, export);

		try (Stream<Path> tmp = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
			assertThat(tmp.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("reconciliation-" + runId));
		}
	}
}
