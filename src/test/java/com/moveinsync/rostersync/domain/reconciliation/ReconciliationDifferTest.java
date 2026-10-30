package com.moveinsync.rostersync.domain.reconciliation;

import static com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction.CREATE;
import static com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction.DEACTIVATE;
import static com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.domain.EmployeeStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconciliationDifferTest {

	private static final Instant AS_OF = Instant.parse("2026-10-30T02:00:00Z");
	private static final Instant BEFORE = AS_OF.minusSeconds(3600);
	private static final Instant AFTER = AS_OF.plusSeconds(300);

	private final ReconciliationDiffer differ = new ReconciliationDiffer();

	private static StoredEntry stored(String id, long version, String hash, EmployeeStatus status, Instant last) {
		return new StoredEntry(id, version, hash, status, last);
	}

	private static StoredEntry active(String id, long version, String hash) {
		return stored(id, version, hash, EmployeeStatus.ACTIVE, BEFORE);
	}

	private static FileEntry row(String id, long version, String hash) {
		return new FileEntry(id, version, hash, EmployeeStatus.ACTIVE, true);
	}

	private static Map<String, FileEntry> file(FileEntry... rows) {
		Map<String, FileEntry> file = new HashMap<>();
		for (FileEntry row : rows) {
			file.put(row.employeeId(), row);
		}
		return file;
	}

	@Test
	void identicalRowsPlanNothing() {
		BucketPlan plan = differ.diff(file(row("E1", 3, "aa")), List.of(active("E1", 3, "aa")), AS_OF);

		assertThat(plan.items()).isEmpty();
		assertThat(plan.conflicts()).isEmpty();
	}

	@Test
	void newerDifferentRowIsAnUpdateAtTheFileVersion() {
		BucketPlan plan = differ.diff(file(row("E1", 4, "bb")), List.of(active("E1", 3, "aa")), AS_OF);

		assertThat(plan.items()).containsExactly(new PlanItem(UPDATE, "E1", 4));
	}

	@Test
	void newerVersionWithTheSameContentChangesNothing() {
		assertThat(differ.diff(file(row("E1", 4, "aa")), List.of(active("E1", 3, "aa")), AS_OF).items()).isEmpty();
	}

	@Test
	void olderFileRowNeverOverwritesTheStore() {
		// Snapshot taken before a live event that is already applied.
		assertThat(differ.diff(file(row("E1", 2, "old")), List.of(active("E1", 3, "aa")), AS_OF).items()).isEmpty();
	}

	@Test
	void sameVersionDifferentContentIsAConflictNotAChange() {
		BucketPlan plan = differ.diff(file(row("E1", 3, "zz")), List.of(active("E1", 3, "aa")), AS_OF);

		assertThat(plan.items()).isEmpty();
		assertThat(plan.conflicts()).containsExactly("E1");
	}

	@Test
	void activeEmployeeMissingFromTheExportIsDeactivatedAtTheStoredVersion() {
		BucketPlan plan = differ.diff(file(), List.of(active("E1", 7, "aa")), AS_OF);

		assertThat(plan.items()).containsExactly(new PlanItem(DEACTIVATE, "E1", 7));
	}

	@Test
	void missingEmployeeChangedAfterTheSnapshotIsLeftAlone() {
		var liveAfterSnapshot = stored("E1", 8, "aa", EmployeeStatus.ACTIVE, AFTER);

		assertThat(differ.diff(file(), List.of(liveAfterSnapshot), AS_OF).items()).isEmpty();
	}

	@Test
	void missingEmployeeChangedExactlyAtTheSnapshotTimeIsDeactivated() {
		var atSnapshot = stored("E1", 8, "aa", EmployeeStatus.ACTIVE, AS_OF);

		assertThat(differ.diff(file(), List.of(atSnapshot), AS_OF).items()).containsExactly(new PlanItem(DEACTIVATE, "E1", 8));
	}

	@Test
	void missingInactiveEmployeeNeedsNothing() {
		var gone = stored("E1", 5, "aa", EmployeeStatus.INACTIVE, BEFORE);

		assertThat(differ.diff(file(), List.of(gone), AS_OF).items()).isEmpty();
	}

	@Test
	void invalidRowKeepsItsEmployeeAndIsNeverApplied() {
		BucketPlan plan = differ.diff(file(FileEntry.invalid("E1"), FileEntry.invalid("E9")),
				List.of(active("E1", 3, "aa")), AS_OF);

		assertThat(plan.items()).isEmpty();
	}

	@Test
	void newEmployeesAreCreatedInIdOrder() {
		BucketPlan plan = differ.diff(file(row("E3", 1, "c"), row("E1", 2, "a"), row("E2", 1, "b")), List.of(), AS_OF);

		assertThat(plan.items()).containsExactly(new PlanItem(CREATE, "E1", 2), new PlanItem(CREATE, "E2", 1),
				new PlanItem(CREATE, "E3", 1));
	}

	@Test
	void exportMarkingSomeoneInactiveWithANewerVersionIsAnUpdate() {
		var leaver = new FileEntry("E1", 4, "inactive-hash", EmployeeStatus.INACTIVE, true);

		assertThat(differ.diff(file(leaver), List.of(active("E1", 3, "aa")), AS_OF).items())
				.containsExactly(new PlanItem(UPDATE, "E1", 4));
	}

	@Test
	void countsByAction() {
		BucketPlan plan = differ.diff(file(row("E2", 4, "new"), row("E3", 1, "x")),
				List.of(active("E1", 1, "a"), active("E2", 3, "old")), AS_OF);

		assertThat(plan.count(CREATE)).isEqualTo(1);
		assertThat(plan.count(UPDATE)).isEqualTo(1);
		assertThat(plan.count(DEACTIVATE)).isEqualTo(1);
	}
}
