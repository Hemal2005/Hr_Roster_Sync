package com.moveinsync.rostersync.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Without the dev or demo profile there is no token endpoint at all.
 */
class DevTokenAbsentIT extends ApiTest {

	@Test
	void devTokenEndpointDoesNotExistOutsideDevAndDemo() throws Exception {
		mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tenantId\":\"acme\",\"subject\":\"x\",\"roles\":[\"ADMIN\"]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
