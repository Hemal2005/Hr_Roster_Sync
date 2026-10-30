package com.moveinsync.rostersync.application.reconciliation;

import static com.moveinsync.rostersync.support.ExportFiles.active;
import static com.moveinsync.rostersync.support.TestRecords.create;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ExportFiles;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.TestRecords;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The threshold is configuration, not code: at 0.50 the same 40% gap that trips the
 * default 0.30 brake is applied.
 */
@TestPropertySource(properties = "rostersync.reconciliation.brake-threshold=0.50")
class BrakeThresholdConfigIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final Instant AS_OF = TestRecords.T0.plusSeconds(3600);

	@Autowired
	ApplyService applyService;

	@Autowired
	ReconciliationService service;

	@Autowired
	ReconciliationStore store;

	@TempDir
	Path dir;

	@Test
	void thresholdComesFromConfiguration() {
		givenTenant("acme", "BLR-01");
		List<String> rows = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			applyService.apply(ACME, "workday", create("E%02d".formatted(i), 1, "BLR-01", "DAY"));
			if (i <= 6) {
				rows.add(active("E%02d".formatted(i), 1, "BLR-01", "DAY"));
			}
		}
		UUID runId = UUID.randomUUID();
		store.createRun(runId, ACME, "export.csv", AS_OF, "workday");

		RunStatus status = service.execute(runId, ACME, AS_OF, ExportFiles.write(dir, "acme", AS_OF, rows, true));

		assertThat(status).isEqualTo(RunStatus.COMPLETED);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE status = 'INACTIVE'", Integer.class))
				.isEqualTo(4);
	}
}
