package com.moveinsync.rostersync.api.reconciliation;

import static com.moveinsync.rostersync.support.ExportFiles.active;
import static com.moveinsync.rostersync.support.TestRecords.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ApiTest;
import com.moveinsync.rostersync.support.ExportFiles;
import com.moveinsync.rostersync.support.TestRecords;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ReconciliationApiIT extends ApiTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final Instant AS_OF = TestRecords.T0.plusSeconds(3600);

	@Autowired
	ApplyService applyService;

	@TempDir
	Path dir;

	@BeforeEach
	void tenActiveEmployees() {
		givenTenant("acme", "BLR-01", "HYD-02");
		givenTenant("globex", "BLR-01");
		for (int i = 1; i <= 10; i++) {
			applyService.apply(ACME, "workday", create("E%02d".formatted(i), 1, "BLR-01", "DAY"));
		}
	}

	private MockMultipartFile export(String tenant, int keep) throws Exception {
		List<String> rows = new ArrayList<>();
		for (int i = 1; i <= keep; i++) {
			rows.add(active("E%02d".formatted(i), 1, "BLR-01", "DAY"));
		}
		Path file = ExportFiles.write(dir, tenant, AS_OF, rows, true);
		return new MockMultipartFile("file", "export.csv", "text/csv", Files.readAllBytes(file));
	}

	private String upload(MockMultipartFile file, String token) throws Exception {
		String body = mvc.perform(multipart("/api/v1/reconciliations").file(file).header("Authorization", token))
				.andExpect(status().isAccepted())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("runId").asString();
	}

	private String operator() {
		return bearer("acme", "ops-1", "OPERATOR");
	}

	private String statusOf(String runId) throws Exception {
		String body = mvc.perform(get("/api/v1/reconciliations/" + runId).header("Authorization", operator()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("status").asString();
	}

	private void awaitStatus(String runId, String expected) {
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(statusOf(runId)).isEqualTo(expected));
	}

	@Test
	void hrSystemUploadsAndTheRunCompletesInTheBackground() throws Exception {
		String runId = upload(export("acme", 9), bearer("acme", "workday", "HR_INTEGRATION"));

		awaitStatus(runId, "COMPLETED");
		mvc.perform(get("/api/v1/reconciliations/" + runId).header("Authorization", operator()))
				.andExpect(jsonPath("$.toDeactivate").value(1))
				.andExpect(jsonPath("$.applied").value(1))
				.andExpect(jsonPath("$.submittedBy").value("workday"));
	}

	@Test
	void trippedRunWaitsAndAnOperatorApprovesIt() throws Exception {
		String runId = upload(export("acme", 5), operator());
		awaitStatus(runId, "AWAITING_APPROVAL");

		mvc.perform(post("/api/v1/reconciliations/" + runId + "/approve").header("Authorization", operator()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.decidedBy").value("ops-1"));

		awaitStatus(runId, "COMPLETED");
		assertThat(activeCount()).isEqualTo(5);
	}

	@Test
	void rejectedRunChangesNothingAndCannotBeDecidedTwice() throws Exception {
		String runId = upload(export("acme", 5), operator());
		awaitStatus(runId, "AWAITING_APPROVAL");

		mvc.perform(post("/api/v1/reconciliations/" + runId + "/reject").header("Authorization", operator()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));
		mvc.perform(post("/api/v1/reconciliations/" + runId + "/approve").header("Authorization", operator()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_NOT_AWAITING_APPROVAL"));

		assertThat(activeCount()).isEqualTo(10);
	}

	@Test
	void completedRunCannotBeApproved() throws Exception {
		String runId = upload(export("acme", 10), operator());
		awaitStatus(runId, "COMPLETED");

		mvc.perform(post("/api/v1/reconciliations/" + runId + "/approve").header("Authorization", operator()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_NOT_AWAITING_APPROVAL"));
	}

	@Test
	void exportForAnotherTenantIsRefusedBeforeAnyRunExists() throws Exception {
		mvc.perform(multipart("/api/v1/reconciliations").file(export("globex", 10)).header("Authorization", operator()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EXPORT_TENANT_MISMATCH"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_run", Long.class)).isZero();
	}

	@Test
	void fileWithoutAHeaderIsRefused() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv",
				(ExportFiles.COLUMNS + "\n").getBytes());

		mvc.perform(multipart("/api/v1/reconciliations").file(file).header("Authorization", operator()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EXPORT_HEADER"));
	}

	@Test
	void missingFilePartNamesTheField() throws Exception {
		mvc.perform(multipart("/api/v1/reconciliations").header("Authorization", operator()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields[0]").value("file"));
	}

	@Test
	void anotherTenantsRunIsNotFound() throws Exception {
		String runId = upload(export("acme", 10), operator());
		awaitStatus(runId, "COMPLETED");

		mvc.perform(get("/api/v1/reconciliations/" + runId).header("Authorization", bearer("globex", "ops-2", "OPERATOR")))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/reconciliations/" + runId + "/approve")
				.header("Authorization", bearer("globex", "ops-2", "OPERATOR")))
				.andExpect(status().isNotFound());
	}

	@Test
	void errorsListLineNumbersAndCodesOnly() throws Exception {
		List<String> rows = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			rows.add(active("E%02d".formatted(i), 1, "BLR-01", "DAY"));
		}
		rows.add(active("E11", 1, "NOPE-9", "DAY"));
		Path file = ExportFiles.write(dir, "acme", AS_OF, rows, true);
		String runId = upload(new MockMultipartFile("file", "export.csv", "text/csv", Files.readAllBytes(file)),
				operator());
		awaitStatus(runId, "COMPLETED");

		String body = mvc.perform(get("/api/v1/reconciliations/" + runId + "/errors").header("Authorization", operator()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].lineNo").value(13))
				.andExpect(jsonPath("$[0].employeeId").value("E11"))
				.andExpect(jsonPath("$[0].code").value("UNKNOWN_OFFICE"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("Priyanka").doesNotContain("@acme.example");
	}

	@Test
	void rolesAreEnforced() throws Exception {
		MockMultipartFile file = export("acme", 10);
		mvc.perform(multipart("/api/v1/reconciliations").file(file).header("Authorization", bearer("acme", "a", "ADMIN")))
				.andExpect(status().isForbidden());

		String runId = upload(file, operator());
		awaitStatus(runId, "COMPLETED");
		for (MockHttpServletRequestBuilder request : List.of(
				get("/api/v1/reconciliations/" + runId),
				get("/api/v1/reconciliations/" + runId + "/errors"),
				post("/api/v1/reconciliations/" + runId + "/approve"),
				post("/api/v1/reconciliations/" + runId + "/reject"))) {
			mvc.perform(request.header("Authorization", bearer("acme", "workday", "HR_INTEGRATION")))
					.andExpect(status().isForbidden());
		}
	}

	private long activeCount() {
		return jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = 'acme' AND status = 'ACTIVE'",
				Long.class);
	}
}
