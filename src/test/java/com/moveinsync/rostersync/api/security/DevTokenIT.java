package com.moveinsync.rostersync.api.security;

import static com.moveinsync.rostersync.support.TestEvents.create;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.support.ApiTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * With the dev profile on, /dev/token issues tokens the API accepts.
 */
@ActiveProfiles("dev")
class DevTokenIT extends ApiTest {

	private String issue(String body) throws Exception {
		String response = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("token").asString();
	}

	@Test
	void issuedTokenIsAcceptedByTheApi() throws Exception {
		givenTenant("acme", "BLR-01");
		String token = issue("""
				{"tenantId":"acme","subject":"workday","roles":["HR_INTEGRATION"]}
				""");

		mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(batch(List.of(create("E000042", 1, "BLR-01", "DAY")))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"));
	}

	@Test
	void issuedTokenCarriesOnlyTheRolesAskedFor() throws Exception {
		givenTenant("acme", "BLR-01");
		String token = issue("""
				{"tenantId":"acme","subject":"E000042","roles":["EMPLOYEE"]}
				""");

		mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(batch(List.of(create("E000042", 1, "BLR-01", "DAY")))))
				.andExpect(status().isForbidden());
	}

	@Test
	void unknownRoleIsRefused() throws Exception {
		mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tenantId\":\"acme\",\"subject\":\"x\",\"roles\":[\"SUPERUSER\"]}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void requestWithoutRolesIsRefused() throws Exception {
		mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tenantId\":\"acme\",\"subject\":\"x\",\"roles\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields[0]").value("roles"));
	}
}
