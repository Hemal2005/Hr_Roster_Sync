package com.moveinsync.rostersync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.ApplyOutcome;
import com.moveinsync.rostersync.application.ApplyResult;
import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.TestRecords;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 32 threads apply versions 1..200 of one employee in shuffled order, all released at
 * the same moment. The row lock and conditional update must serialise them so that the
 * newest version wins, no version is applied twice and no applied change is lost.
 */
class ConcurrentApplyIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final int THREADS = 32;
	private static final int VERSIONS = 200;

	@Autowired
	ApplyService applyService;

	private static IncomingHrRecord version(long v) {
		return new IncomingHrRecord(v == 1 ? HrEventType.CREATE : HrEventType.UPDATE, "E000042", v,
				TestRecords.T0.plusSeconds(v), "Priyanka", "Raghunathan", "e000042@acme.example", TestRecords.PHONE,
				"E000001", v % 2 == 0 ? "BLR-01" : "HYD-02", "SHIFT-" + v);
	}

	@Test
	void concurrentOutOfOrderVersionsConverge() throws Exception {
		givenTenant("acme", "BLR-01", "HYD-02");
		List<Long> versions = new ArrayList<>();
		for (long v = 1; v <= VERSIONS; v++) {
			versions.add(v);
		}
		Collections.shuffle(versions, new Random(2026));

		Map<Long, ApplyResult> results = new ConcurrentHashMap<>();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		List<Future<?>> tasks = new ArrayList<>();
		for (long v : versions) {
			tasks.add(pool.submit(() -> {
				start.await();
				results.put(v, applyService.apply(ACME, "workday", version(v)));
				return null;
			}));
		}
		start.countDown();
		for (Future<?> task : tasks) {
			task.get(60, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(results).hasSize(VERSIONS);
		assertThat(results.values()).extracting(ApplyResult::outcome)
				.containsOnly(ApplyOutcome.APPLIED, ApplyOutcome.STALE);
		assertThat(results.get((long) VERSIONS).outcome()).isEqualTo(ApplyOutcome.APPLIED);

		Map<String, Object> row = jdbc.queryForMap("SELECT hr_version, shift_code, office_code FROM employee");
		assertThat(row.get("hr_version")).isEqualTo((long) VERSIONS);
		assertThat(row.get("shift_code")).isEqualTo("SHIFT-" + VERSIONS);
		assertThat(row.get("office_code")).isEqualTo("BLR-01");

		List<Long> appliedInCommitOrder = jdbc.queryForList(
				"SELECT version FROM employee_event_log ORDER BY id", Long.class);
		long appliedResults = results.values().stream().filter(r -> r.outcome() == ApplyOutcome.APPLIED).count();
		assertThat(appliedInCommitOrder).hasSize((int) appliedResults).doesNotHaveDuplicates().isSorted();
		assertThat(appliedInCommitOrder.getLast()).isEqualTo(VERSIONS);
	}
}
