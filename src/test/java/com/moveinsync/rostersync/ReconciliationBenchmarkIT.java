package com.moveinsync.rostersync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.reconciliation.ReconciliationRun;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationService;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore;
import com.moveinsync.rostersync.application.reconciliation.RunStatus;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import com.moveinsync.rostersync.support.EmployeeSeeder;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.tools.ExportGenerator;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One full night at the case study's size: 200,000 stored employees against a 201,000-row
 * export with 1,000 changed, 1,000 missing and 995 new employees. Run with
 * {@code ./mvnw verify -Pbenchmark}, which gives the test JVM a 256 MB heap; results go
 * to {@code target/benchmark/reconciliation.txt}.
 */
@Tag("benchmark")
class ReconciliationBenchmarkIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final int STORED = 200_000;
	private static final Duration BUDGET = Duration.ofSeconds(60);

	@Autowired
	ReconciliationService reconciliation;

	@Autowired
	ReconciliationStore store;

	@Autowired
	BucketAssigner buckets;

	@Autowired
	DataSource dataSource;

	@Test
	void fullNightFitsTheBudget(@TempDir Path dir) throws Exception {
		givenTenant("acme", ExportGenerator.DEFAULT_OFFICES.toArray(String[]::new));
		Path stored = ExportGenerator.write(dir.resolve("stored.csv"), "acme", ExportGenerator.DEFAULT_AS_OF, STORED,
				null, ExportGenerator.DEFAULT_OFFICES);
		new EmployeeSeeder(dataSource, buckets).seedFrom(ACME, stored, dir.resolve("seed.csv"),
				ExportGenerator.DEFAULT_AS_OF.minusSeconds(86_400));
		Path tonight = ExportGenerator.write(dir.resolve("tonight.csv"), "acme", ExportGenerator.DEFAULT_AS_OF,
				STORED + 1000, null, ExportGenerator.DEFAULT_OFFICES, 100, 200);

		System.gc();
		List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream()
				.filter(pool -> pool.getType() == MemoryType.HEAP)
				.toList();
		heap.forEach(MemoryPoolMXBean::resetPeakUsage);
		UUID runId = UUID.randomUUID();
		store.createRun(runId, ACME, "tonight.csv", ExportGenerator.DEFAULT_AS_OF, "benchmark");
		long started = System.nanoTime();
		RunStatus status = reconciliation.execute(runId, ACME, ExportGenerator.DEFAULT_AS_OF, tonight);
		Duration total = Duration.ofNanos(System.nanoTime() - started);
		// Each pool's own peak, summed: an upper bound on the heap the run needed.
		long peakHeap = heap.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
		long maxHeap = Runtime.getRuntime().maxMemory();

		ReconciliationRun run = store.find(ACME, runId).orElseThrow();
		Duration planning = Duration.between(run.createdAt(), run.plannedAt());
		String report = """
				reconciliation benchmark
				stored employees: %d
				export rows: %d (create %d, update %d, deactivate %d, conflicts %d, invalid %d)
				status: %s (applied %d, skipped %d)
				plan: %d ms
				plan + apply: %d ms
				peak heap (sum of pool peaks): %d MB of max %d MB
				""".formatted(STORED, run.rowsRead(), run.toCreate(), run.toUpdate(), run.toDeactivate(),
				run.conflicts(), run.rowsInvalid(), status, run.applied(), run.skipped(), planning.toMillis(),
				total.toMillis(), peakHeap / (1024 * 1024), maxHeap / (1024 * 1024));
		Path out = Path.of("target", "benchmark", "reconciliation.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report, StandardCharsets.UTF_8);
		System.out.println(report);

		assertThat(status).isEqualTo(RunStatus.COMPLETED);
		assertThat(run.toCreate()).isEqualTo(995);
		assertThat(run.toUpdate()).isEqualTo(1000);
		assertThat(run.toDeactivate()).isEqualTo(1000);
		assertThat(run.applied()).isEqualTo(2995);
		assertThat(planning).isLessThan(BUDGET);
		assertThat(total).isLessThan(BUDGET);
	}
}
