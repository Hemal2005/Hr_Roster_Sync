package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestEvents.create;
import static com.moveinsync.rostersync.support.TestEvents.event;
import static com.moveinsync.rostersync.support.TestEvents.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.application.OutboxRelay;
import com.moveinsync.rostersync.support.ApiTest;
import com.moveinsync.rostersync.support.ExportFiles;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Runs every flow that touches personal data, with values that appear nowhere else, and
 * then searches everything the application wrote to its logs, its metrics and its error
 * bodies for them. Valid and invalid values both count: a rejected email is still an email.
 */
@ExtendWith(OutputCaptureExtension.class)
class NoPiiInLogsIT extends ApiTest {

	private static final String FIRST = "Zephyrine";
	private static final String LAST = "Quillfeather";
	private static final String EMAIL = "zephyrine.q@pii-probe.example";
	private static final String BAD_EMAIL = "zephyrine.at.pii-probe";
	private static final String PHONE = "+919811122233";
	private static final String BAD_PHONE = "+9198111222";
	private static final String PICKUP = "Lotus Towers Gate 7";
	private static final String ASSISTANCE = "needs ramp, Quillfeather family";
	private static final List<String> PERSONAL = List.of(FIRST, LAST, "pii-probe", "9811122233", "981112223",
			"Lotus Towers", "needs ramp");

	@Autowired
	OutboxRelay relay;

	private final List<String> errorBodies = new ArrayList<>();

	private static Map<String, Object> person(Map<String, Object> event) {
		Map<String, Object> copy = new HashMap<>(event);
		copy.put("firstName", FIRST);
		copy.put("lastName", LAST);
		copy.put("email", EMAIL);
		copy.put("phone", PHONE);
		return copy;
	}

	private static Map<String, Object> with(Map<String, Object> event, String field, Object value) {
		Map<String, Object> copy = new HashMap<>(event);
		copy.put(field, value);
		return copy;
	}

	private ResultActions send(String body) throws Exception {
		return mvc.perform(post("/api/v1/hr/events:batch")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday", "HR_INTEGRATION"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private void keepErrorBody(ResultActions result) throws Exception {
		errorBodies.add(result.andReturn().getResponse().getContentAsString());
	}

	private ResultActions patchTransport(String ifMatch, String body) throws Exception {
		return mvc.perform(patch("/api/v1/employees/P1/transport")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN"))
				.header(HttpHeaders.IF_MATCH, ifMatch)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	@Test
	void noPersonalValueReachesLogsMetricsOrErrorBodies(CapturedOutput output) throws Exception {
		givenTenant("acme", "BLR-01", "HYD-02");
		jdbc.update("INSERT INTO trip (tenant_id, id, office_code, pickup_time, capacity) "
				+ "VALUES ('acme', 'T1', 'BLR-01', now() + interval '1 day', 4)");

		// Ingest: valid, invalid email, invalid phone, wrongly typed field, foreign field.
		keepErrorBody(send(batch(List.of(
				with(person(create("P1", 1, "BLR-01", "DAY")), "pickupPoint", PICKUP),
				with(person(create("P2", 1, "BLR-01", "DAY")), "email", BAD_EMAIL),
				with(person(create("P3", 1, "BLR-01", "DAY")), "phone", BAD_PHONE),
				with(person(create("P4", 1, "BLR-01", "DAY")), "version", FIRST),
				person(create("P5", 1, "NOPE-9", "DAY")))))
				.andExpect(status().isOk()));
		// Malformed JSON with personal values in it.
		keepErrorBody(send("{\"sourceSystem\":\"workday\",\"events\":[{\"firstName\":\"" + FIRST + "\",\"email\":\""
				+ EMAIL).andExpect(status().isBadRequest()));
		// Same version, different content: a conflict.
		keepErrorBody(send(batch(List.of(with(person(create("P1", 1, "BLR-01", "DAY")), "lastName", LAST + "son"))))
				.andExpect(status().isOk()));

		// Transport edits, including a stale ETag and an HR-owned field.
		patchTransport("\"0\"", "{\"pickupPoint\":\"" + PICKUP + "\",\"specialAssistance\":\"" + ASSISTANCE + "\"}")
				.andExpect(status().isOk());
		keepErrorBody(patchTransport("\"0\"", "{\"pickupPoint\":\"" + PICKUP + " B\"}")
				.andExpect(status().isPreconditionFailed()));
		keepErrorBody(patchTransport("\"1\"", "{\"email\":\"" + EMAIL + "\"}").andExpect(status().isBadRequest()));
		mvc.perform(get("/api/v1/employees/P1").header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN")))
				.andExpect(status().isOk());

		// A booking, then the exit that cancels it, through the outbox, broker and consumer.
		mvc.perform(post("/api/v1/bookings")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"tripId\":\"T1\",\"employeeId\":\"P1\"}"))
				.andExpect(status().isCreated());
		send(batch(List.of(person(update("P1", 2, "HYD-02", "NIGHT")), person(event("DEACTIVATE", "P1", 3, "HYD-02",
				"NIGHT"))))).andExpect(status().isOk());
		relay.relayOnce();
		await().atMost(Duration.ofSeconds(30)).until(() -> output.getAll().contains("deactivation handled"));

		// Nightly file with personal values, valid and invalid.
		String row = String.join(",", "P6", "1", FIRST, LAST, EMAIL, PHONE, "E000001", "BLR-01", "DAY", "ACTIVE");
		String badRow = String.join(",", "P7", "1", FIRST, LAST, BAD_EMAIL, BAD_PHONE, "E000001", "BLR-01", "DAY", "ACTIVE");
		var export = ExportFiles.write(Files.createTempDirectory("pii"), "acme",
				Instant.parse("2026-10-30T02:00:00Z"), List.of(row, badRow, FIRST + "," + EMAIL), true);
		String runBody = mvc.perform(multipart("/api/v1/reconciliations")
				.file(new MockMultipartFile("file", "export.csv", "text/csv", Files.readAllBytes(export)))
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday", "HR_INTEGRATION")))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		String runId = json.readTree(runBody).get("runId").asString();
		await().atMost(Duration.ofSeconds(30)).until(() -> output.getAll().contains("safety brake tripped")
				|| output.getAll().contains("reconciliation applied="));
		keepErrorBody(mvc.perform(get("/api/v1/reconciliations/" + runId + "/errors")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "ops-1", "OPERATOR"))).andExpect(status().isOk()));

		String metrics = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();

		// The capture really holds the application's logs, including the consumer thread's.
		assertThat(output.getAll().contains("hr event rejected") && output.getAll().contains("deactivation handled")
				&& output.getAll().contains("transport fields")).as("application log lines were captured").isTrue();
		// Failure messages name the probe by index only, so they never print the logs.
		String logs = output.getAll();
		for (int i = 0; i < PERSONAL.size(); i++) {
			String value = PERSONAL.get(i);
			assertThat(logs.contains(value)).as("logs contain personal probe #%d", i).isFalse();
			assertThat(metrics.contains(value)).as("metrics contain personal probe #%d", i).isFalse();
			assertThat(errorBodies.stream().anyMatch(body -> body.contains(value)))
					.as("an error body contains personal probe #%d", i).isFalse();
		}
	}
}
