package com.moveinsync.rostersync.api.ingest;

import static com.moveinsync.rostersync.support.TestEvents.create;
import static com.moveinsync.rostersync.support.TestEvents.update;
import static com.moveinsync.rostersync.support.TestEvents.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.support.ApiTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class IngestControllerIT extends ApiTest {

	private static final String URL = "/api/v1/hr/events:batch";

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
}
