package com.moveinsync.rostersync.api.security;

import static com.moveinsync.rostersync.support.TestEvents.create;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.support.ApiTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Who may call what, endpoint by endpoint. Every refusal carries a problem body with a
 * code and the trace id.
 */
class SecurityIT extends ApiTest {

	private static final String INGEST = "/api/v1/hr/events:batch";

	@BeforeEach
	void tenant() {
		givenTenant("acme", "BLR-01");
	}

	private ResultActions ingest(String authorization) throws Exception {
		var request = post(INGEST).contentType(MediaType.APPLICATION_JSON)
				.content(batch(List.of(create("E000042", 1, "BLR-01", "DAY"))));
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return mvc.perform(request);
	}

	@Test
	void ingestWithoutATokenIs401WithAProblemBody() throws Exception {
		ingest(null)
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void expiredTokenIs401() throws Exception {
		ingest(expiredBearer("acme", "HR_INTEGRATION")).andExpect(status().isUnauthorized());
	}

	@Test
	void tokenSignedWithAnotherKeyIs401() throws Exception {
		byte[] otherKey = "some-other-signing-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

		ingest(bearerSignedWith(otherKey, "acme", "HR_INTEGRATION")).andExpect(status().isUnauthorized());
	}

	@Test
	void garbageTokenIs401() throws Exception {
		ingest("Bearer not.a.jwt").andExpect(status().isUnauthorized());
	}

	@Test
	void tokenWithoutATenantIs401() throws Exception {
		ingest(bearer(null, "workday", "HR_INTEGRATION")).andExpect(status().isUnauthorized());
	}

	@ParameterizedTest
	@ValueSource(strings = {"EMPLOYEE", "ADMIN", "OPERATOR"})
	void onlyTheHrIntegrationRoleMayIngest(String role) throws Exception {
		ingest(bearer("acme", "someone", role))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void tokenWithNoRolesMayNotIngest() throws Exception {
		ingest(bearer("acme", "someone")).andExpect(status().isForbidden());
	}

	@Test
	void hrIntegrationMayIngest() throws Exception {
		ingest(bearer("acme", "workday", "HR_INTEGRATION")).andExpect(status().isOk());
	}

	@Test
	void anUnlistedEndpointIsDeniedEvenWithAValidToken() throws Exception {
		mvc.perform(get("/api/v1/something-new").header(HttpHeaders.AUTHORIZATION, bearer("acme", "x", "ADMIN")))
				.andExpect(status().isForbidden());
	}
}
