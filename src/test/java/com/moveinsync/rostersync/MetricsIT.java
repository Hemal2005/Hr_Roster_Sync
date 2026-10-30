package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.ExportFiles.active;
import static com.moveinsync.rostersync.support.TestEvents.create;
import static com.moveinsync.rostersync.support.TestEvents.update;
import static com.moveinsync.rostersync.support.TestEvents.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.application.OutboxRelay;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationService;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ApiTest;
import com.moveinsync.rostersync.support.ExportFiles;
import com.moveinsync.rostersync.support.TestRecords;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Everything on-call needs is on /actuator/prometheus after one burst of real traffic:
 * ingest outcomes by code, lag, retries, outbox depth and age, publishing, consumer lag,
 * reconciliation duration and brake trips, and cache hits.
 */
class MetricsIT extends ApiTest {

	private static final TenantId ACME = new TenantId("acme");

	@Autowired
	OutboxRelay relay;

	@Autowired
	ReconciliationService reconciliation;

	@Autowired
	ReconciliationStore reconciliationStore;

	@TempDir
	Path dir;

	private String scrape() throws Exception {
		return mvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private void ingest(List<Map<String, Object>> events) throws Exception {
		mvc.perform(post("/api/v1/hr/events:batch")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday", "HR_INTEGRATION"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(batch(events)))
				.andExpect(status().isOk());
	}

	@Test
	void healthIsOpenAndShowsOnlyTheStatus() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
		mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
	}

	@Test
	void otherActuatorEndpointsAreNotExposed() throws Exception {
		for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/metrics")) {
			mvc.perform(get(path)).andExpect(status().is4xxClientError());
		}
	}

	@Test
	void everyMonitoringSeriesIsExposed() throws Exception {
		givenTenant("acme", "BLR-01", "HYD-02");
		ingest(List.of(
				create("E1", 1, "BLR-01", "DAY"),
				create("E2", 1, "BLR-01", "DAY"),
				with(create("E3", 1, "BLR-01", "DAY"), "email", "not-an-email"),
				create("E4", 1, "NOPE-9", "DAY")));
		ingest(List.of(
				create("E1", 1, "BLR-01", "DAY"),
				with(update("E2", 3, "HYD-02", "DAY"), "pickupPoint", "Gate 1")));
		ingest(List.of(update("E2", 2, "BLR-01", "DAY")));
		mvc.perform(get("/api/v1/employees/E1").header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN")))
				.andExpect(status().isOk());
		String beforeRelay = scrape();
		relay.relayOnce();

		Instant asOf = TestRecords.T0.plusSeconds(3600);
		UUID runId = UUID.randomUUID();
		reconciliationStore.createRun(runId, ACME, "export.csv", asOf, "workday");
		reconciliation.execute(runId, ACME, asOf, ExportFiles.write(dir, "acme", asOf,
				List.of(active("E1", 1, "BLR-01", "DAY")), false));

		String metrics = scrape();
		assertThat(metrics)
				.contains("hr_events_total{application=\"hr-roster-sync\",code=\"NONE\",outcome=\"APPLIED\",tenant=\"acme\"}")
				.contains("code=\"INVALID_EMAIL\",outcome=\"REJECTED\"")
				.contains("code=\"UNKNOWN_OFFICE\",outcome=\"REJECTED\"")
				.contains("code=\"NONE\",outcome=\"DUPLICATE\"")
				.contains("code=\"NONE\",outcome=\"STALE\"")
				.contains("hr_ingest_lag_seconds_bucket{")
				.contains("hr_ingest_lag_seconds_count{application=\"hr-roster-sync\",tenant=\"acme\"}")
				.contains("hr_apply_retries_total")
				.contains("hr_owned_field_violation_total{application=\"hr-roster-sync\",field=\"pickupPoint\",tenant=\"acme\"}")
				.contains("outbox_depth")
				.contains("outbox_oldest_age_seconds")
				.contains("outbox_published_total")
				.contains("outbox_publish_failures_total")
				.contains("reconciliation_duration_seconds_count{application=\"hr-roster-sync\",phase=\"plan\",result=\"TRIPPED\",tenant=\"acme\"}")
				.contains("reconciliation_brake_tripped_total{application=\"hr-roster-sync\",reason=\"FILE_TRUNCATED\",tenant=\"acme\"}")
				.contains("cache_gets_total{application=\"hr-roster-sync\",cache=\"offices\"")
				.contains("cache_gets_total{application=\"hr-roster-sync\",cache=\"employees\"");
		assertThat(gauge(beforeRelay, "outbox_depth")).isEqualTo(3.0);
		assertThat(gauge(metrics, "outbox_depth")).isZero();
		assertThat(gauge(metrics, "outbox_published_total")).isGreaterThanOrEqualTo(3.0);

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(scrape()).contains("kafka_consumer_fetch_manager_records_lag"));
	}

	/** The value of an untagged-except-application series. */
	private static double gauge(String metrics, String name) {
		return metrics.lines()
				.filter(line -> line.startsWith(name + "{"))
				.mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
				.sum();
	}
}
