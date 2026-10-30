package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.support.ApiTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Sends a 1,000-event feed, then sends exactly the same feed again. The second run must
 * change nothing: same rows, same column values, same timestamps, same applied-event log.
 */
class ReplayFeedTwiceIT extends ApiTest {

	private static final int EMPLOYEES = 200;
	private static final int VERSIONS = 5;

	private List<Map<String, Object>> feed() {
		List<Map<String, Object>> events = new ArrayList<>();
		for (int e = 1; e <= EMPLOYEES; e++) {
			String id = "E%06d".formatted(e);
			events.add(event("CREATE", id, 1, "BLR-01", "DAY"));
			events.add(event("UPDATE", id, 2, "BLR-01", "NIGHT"));
			events.add(event("UPDATE", id, 3, "HYD-02", "NIGHT"));
			events.add(event("UPDATE", id, 4, "HYD-02", "DAY"));
			// Every tenth employee leaves at v5; exits carry the full record like the others.
			events.add(event(e % 10 == 0 ? "DEACTIVATE" : "UPDATE", id, 5, "BLR-01", "DAY"));
		}
		Collections.shuffle(events, new Random(7));
		return events;
	}

	private List<String> send(String body) throws Exception {
		String response = mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday-connector", "HR_INTEGRATION"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> outcomes = new ArrayList<>();
		for (JsonNode result : json.readTree(response).get("results")) {
			outcomes.add(result.get("outcome").asString());
		}
		return outcomes;
	}

	/** Every column of every row, as text, so bytes and timestamps compare exactly. */
	private List<String> snapshot(String table, String orderBy) {
		return jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY " + orderBy, String.class);
	}

	@Test
	void replayFeedTwiceChangesNothing() throws Exception {
		givenTenant("acme", "BLR-01", "HYD-02");
		String body = batch(feed());

		List<String> first = send(body);
		List<String> employeesAfterFirst = snapshot("employee", "employee_id");
		List<String> logAfterFirst = snapshot("employee_event_log", "id");

		List<String> second = send(body);

		assertThat(first).hasSize(EMPLOYEES * VERSIONS).doesNotContain("REJECTED");
		assertThat(employeesAfterFirst).hasSize(EMPLOYEES);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE hr_version <> 5", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE status = 'INACTIVE'", Integer.class))
				.isEqualTo(EMPLOYEES / 10);

		assertThat(second).hasSize(EMPLOYEES * VERSIONS).containsOnly("DUPLICATE", "STALE");
		assertThat(second.stream().filter("DUPLICATE"::equals).count()).isEqualTo(EMPLOYEES);
		assertThat(snapshot("employee", "employee_id")).isEqualTo(employeesAfterFirst);
		assertThat(snapshot("employee_event_log", "id")).isEqualTo(logAfterFirst);
	}
}
