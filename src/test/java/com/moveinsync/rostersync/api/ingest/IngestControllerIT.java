package com.moveinsync.rostersync.api.ingest;

import static com.moveinsync.rostersync.support.TestEvents.create;
import static com.moveinsync.rostersync.support.TestEvents.update;
import static com.moveinsync.rostersync.support.TestEvents.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.support.ApiTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class IngestControllerIT extends ApiTest {

	private static final String URL = "/api/v1/hr/events:batch";

	@Autowired
	MeterRegistry meters;

	@BeforeEach
	void tenants() {
		givenTenant("acme", "BLR-01", "HYD-02");
		givenTenant("globex", "BLR-01");
	}

	private ResultActions send(String authorization, String body) throws Exception {
		var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return mvc.perform(request);
	}

	@Test
	void batchReturnsPerRecordResults() throws Exception {
		var first = create("E000042", 1, "BLR-01", "DAY");
		var body = batch(List.of(first, first, with(update("E000043", 1, "BLR-01", "DAY"), "phone", "12ab")));

		send(bearer("acme", "workday-connector", "HR_INTEGRATION"), body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.results.length()").value(3))
				.andExpect(jsonPath("$.results[0].index").value(0))
				.andExpect(jsonPath("$.results[0].employeeId").value("E000042"))
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"))
				.andExpect(jsonPath("$.results[0].errorCode").doesNotExist())
				.andExpect(jsonPath("$.results[1].outcome").value("DUPLICATE"))
				.andExpect(jsonPath("$.results[2].outcome").value("REJECTED"))
				.andExpect(jsonPath("$.results[2].errorCode").value("INVALID_PHONE"));
	}

	@Test
	void tenantComesFromTheTokenNotTheBody() throws Exception {
		var event = with(create("E000042", 1, "BLR-01", "DAY"), "tenantId", "globex");

		send(bearer("acme", "workday-connector", "HR_INTEGRATION"), batch(List.of(event))).andExpect(status().isOk());

		assertThat(jdbc.queryForList("SELECT tenant_id FROM employee", String.class)).containsExactly("acme");
	}

	@Test
	void requestWithoutATokenIsRefused() throws Exception {
		send(null, batch(List.of(create("E000042", 1, "BLR-01", "DAY")))).andExpect(status().isUnauthorized());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class)).isZero();
	}

	@Test
	void tokenWithoutATenantIsRefused() throws Exception {
		send(bearer(null, "workday-connector", "HR_INTEGRATION"), batch(List.of(create("E000042", 1, "BLR-01", "DAY"))))
				.andExpect(status().isUnauthorized());
	}

	private String hr() {
		return bearer("acme", "workday-connector", "HR_INTEGRATION");
	}

	@Test
	void oneBadRecordDoesNotFailBatch() throws Exception {
		List<Map<String, Object>> events = new ArrayList<>();
		for (int i = 1; i <= 100; i++) {
			events.add(create("E%06d".formatted(i), 1, "BLR-01", "DAY"));
		}
		events.set(36, with(events.get(36), "phone", "not-a-phone"));

		send(hr(), batch(events))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.results[?(@.outcome == 'APPLIED')]", hasSize(99)))
				.andExpect(jsonPath("$.results[?(@.outcome == 'REJECTED')]", hasSize(1)))
				.andExpect(jsonPath("$.results[36].outcome").value("REJECTED"))
				.andExpect(jsonPath("$.results[36].employeeId").value("E000037"))
				.andExpect(jsonPath("$.results[36].errorCode").value("INVALID_PHONE"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class)).isEqualTo(99);
	}

	@Test
	void wronglyTypedFieldRejectsOnlyItsRecord() throws Exception {
		var good = create("E000001", 1, "BLR-01", "DAY");
		var badVersion = with(create("E000002", 1, "BLR-01", "DAY"), "version", "seven");

		send(hr(), batch(List.of(good, badVersion)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"))
				.andExpect(jsonPath("$.results[1].outcome").value("REJECTED"))
				.andExpect(jsonPath("$.results[1].errorCode").value("MALFORMED_RECORD"));
	}

	@Test
	void unknownEventTypeRejectsOnlyItsRecord() throws Exception {
		var promote = with(create("E000002", 1, "BLR-01", "DAY"), "type", "PROMOTE");

		send(hr(), batch(List.of(create("E000001", 1, "BLR-01", "DAY"), promote)))
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"))
				.andExpect(jsonPath("$.results[1].errorCode").value("INVALID_EVENT_TYPE"));
	}

	@Test
	void eventThatIsNotAnObjectRejectsOnlyItself() throws Exception {
		String body = """
				{"sourceSystem":"workday","events":[42, %s]}
				""".formatted(json.writeValueAsString(create("E000001", 1, "BLR-01", "DAY")));

		send(hr(), body)
				.andExpect(jsonPath("$.results[0].errorCode").value("MALFORMED_RECORD"))
				.andExpect(jsonPath("$.results[1].outcome").value("APPLIED"));
	}

	@Test
	void errorReportContainsNoPersonalData() throws Exception {
		var badPhone = with(create("E000001", 1, "BLR-01", "DAY"), "phone", "+91-98765-HELLO");
		var badEmail = with(create("E000002", 1, "BLR-01", "DAY"), "email", "priyanka-at-acme");
		var badOffice = with(create("E000003", 1, "SECRET-OFFICE", "DAY"), "firstName", "Raghunathan");

		String response = send(hr(), batch(List.of(badPhone, badEmail, badOffice)))
				.andExpect(jsonPath("$.results[*].errorCode", contains("INVALID_PHONE", "INVALID_EMAIL", "UNKNOWN_OFFICE")))
				.andReturn().getResponse().getContentAsString();

		assertThat(response).doesNotContain("98765", "HELLO", "priyanka-at-acme", "SECRET-OFFICE", "Raghunathan",
				"Priyanka");
	}

	@Test
	void transportFieldsInAnHrPayloadAreIgnoredAndCounted() throws Exception {
		send(hr(), batch(List.of(create("E000042", 1, "BLR-01", "DAY"))));
		jdbc.update("UPDATE employee SET pickup_point = 'Gate 2', transport_version = 1 WHERE employee_id = 'E000042'");
		double before = violations("pickupPoint");

		var withPickup = with(update("E000042", 2, "HYD-02", "NIGHT"), "pickupPoint", "Somewhere else");
		send(hr(), batch(List.of(withPickup))).andExpect(jsonPath("$.results[0].outcome").value("APPLIED"));

		assertThat(jdbc.queryForObject("SELECT pickup_point FROM employee", String.class)).isEqualTo("Gate 2");
		assertThat(jdbc.queryForObject("SELECT office_code FROM employee", String.class)).isEqualTo("HYD-02");
		assertThat(violations("pickupPoint")).isEqualTo(before + 1);
	}

	private double violations(String field) {
		var counter = meters.find("hr.owned.field.violation").tags("tenant", "acme", "field", field).counter();
		return counter == null ? 0 : counter.count();
	}

	@Test
	void batchOverTheLimitIsRefusedWhole() throws Exception {
		List<Map<String, Object>> events = new ArrayList<>();
		for (int i = 1; i <= BatchRequest.MAX_EVENTS + 1; i++) {
			events.add(create("E%06d".formatted(i), 1, "BLR-01", "DAY"));
		}

		send(hr(), batch(events))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.fields", contains("events")));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class)).isZero();
	}

	@Test
	void emptyBatchIsRefused() throws Exception {
		send(hr(), batch(List.of())).andExpect(status().isBadRequest());
	}

	@Test
	void bodyThatIsNotJsonIsMalformed() throws Exception {
		send(hr(), "{\"sourceSystem\": workday")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}
}
