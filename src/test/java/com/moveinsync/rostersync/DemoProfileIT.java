package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestEvents.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.support.PostgresContainerConfig;
import com.moveinsync.rostersync.support.RedpandaContainerConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * What docker compose runs: the demo profile seeds two tenants with offices and trips,
 * and /dev/token issues tokens the API accepts. Not an IntegrationTest subclass, whose
 * table reset would remove the seed this test checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@Import({PostgresContainerConfig.class, RedpandaContainerConfig.class})
class DemoProfileIT {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	@Test
	void seedHasTwoTenantsWithOfficesAndTrips() {
		assertThat(jdbc.queryForList("SELECT id FROM tenant ORDER BY id", String.class)).containsExactly("acme", "globex");
		assertThat(jdbc.queryForList("SELECT tenant_id || '/' || code FROM office WHERE active ORDER BY 1", String.class))
				.containsExactly("acme/BLR-01", "acme/HYD-02", "globex/BLR-01", "globex/CHN-01");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM trip WHERE pickup_time > now()", Long.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee", Long.class)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE description = 'demo seed' AND success", Long.class))
				.isEqualTo(1);
	}

	@Test
	void devTokenWorksAndTheSeededOfficesAcceptEvents() throws Exception {
		String response = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tenantId\":\"globex\",\"subject\":\"workday\",\"roles\":[\"HR_INTEGRATION\"]}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String token = json.readTree(response).get("token").asString();

		mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("sourceSystem", "workday",
								"events", List.of(create("G000001", 1, "CHN-01", "DAY"))))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"));
	}
}
