package com.moveinsync.rostersync.api.employee;

import static com.moveinsync.rostersync.support.TestEvents.update;
import static com.moveinsync.rostersync.support.TestRecords.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ApiTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TransportPatchIT extends ApiTest {

	private static final String URL = "/api/v1/employees/E000042/transport";

	@Autowired
	ApplyService applyService;

	@BeforeEach
	void employee() {
		givenTenant("acme", "BLR-01", "HYD-02");
		applyService.apply(new TenantId("acme"), "workday", create("E000042", 3, "BLR-01", "DAY"));
	}

	private ResultActions patchAs(String role, String ifMatch, String body) throws Exception {
		var request = patch(URL).header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", role))
				.contentType(MediaType.APPLICATION_JSON).content(body);
		if (ifMatch != null) {
			request.header(HttpHeaders.IF_MATCH, ifMatch);
		}
		return mvc.perform(request);
	}

	private ResultActions edit(String ifMatch, String body) throws Exception {
		return patchAs("ADMIN", ifMatch, body);
	}

	private Map<String, Object> row() {
		return jdbc.queryForMap(
				"SELECT pickup_point, special_assistance, transport_version, hr_version FROM employee");
	}

	@Test
	void patchWithTheCurrentETagUpdatesAndReturnsTheNextOne() throws Exception {
		edit("\"0\"", "{\"pickupPoint\":\"Gate 2, Tech Park\",\"specialAssistance\":\"wheelchair\"}")
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
				.andExpect(jsonPath("$.pickupPoint").value("Gate 2, Tech Park"))
				.andExpect(jsonPath("$.transportVersion").value(1));

		assertThat(row()).containsEntry("pickup_point", "Gate 2, Tech Park")
				.containsEntry("special_assistance", "wheelchair")
				.containsEntry("transport_version", 1L)
				.containsEntry("hr_version", 3L);
	}

	@Test
	void staleIfMatchIs412AndChangesNothing() throws Exception {
		edit("\"0\"", "{\"pickupPoint\":\"Gate 2\"}").andExpect(status().isOk());

		edit("\"0\"", "{\"pickupPoint\":\"Gate 5\"}")
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("TRANSPORT_VERSION_MISMATCH"));
		assertThat(row()).containsEntry("pickup_point", "Gate 2").containsEntry("transport_version", 1L);
	}

	@Test
	void missingIfMatchIs428() throws Exception {
		edit(null, "{\"pickupPoint\":\"Gate 2\"}")
				.andExpect(status().isPreconditionRequired())
				.andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
		assertThat(row()).containsEntry("transport_version", 0L);
	}

	@Test
	void unparseableIfMatchIs400() throws Exception {
		edit("\"abc\"", "{\"pickupPoint\":\"Gate 2\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields", contains("If-Match")));
	}

	@Test
	void hrOwnedFieldInTheBodyIsRefusedAndNothingChanges() throws Exception {
		edit("\"0\"", "{\"pickupPoint\":\"Gate 2\",\"officeCode\":\"HYD-02\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FIELD_NOT_OWNED"))
				.andExpect(jsonPath("$.fields", contains("officeCode")));
		assertThat(row()).containsEntry("pickup_point", null).containsEntry("transport_version", 0L);
	}

	@Test
	void unknownFieldIsRefused() throws Exception {
		edit("\"0\"", "{\"pickupPont\":\"Gate 2\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.fields", contains("pickupPont")));
	}

	@Test
	void absentFieldIsKeptAndNullClearsIt() throws Exception {
		edit("\"0\"", "{\"pickupPoint\":\"Gate 2\",\"specialAssistance\":\"wheelchair\"}");

		edit("\"1\"", "{\"specialAssistance\":null}").andExpect(status().isOk());

		assertThat(row()).containsEntry("pickup_point", "Gate 2").containsEntry("special_assistance", null);
	}

	@Test
	void nonTextValueIsRefused() throws Exception {
		edit("\"0\"", "{\"pickupPoint\":42}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields", contains("pickupPoint")));
	}

	@Test
	void laterHrUpdatesKeepTheTransportFields() throws Exception {
		edit("\"0\"", "{\"pickupPoint\":\"Gate 2, Tech Park\"}").andExpect(status().isOk());

		mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday", "HR_INTEGRATION"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(batch(List.of(update("E000042", 4, "HYD-02", "NIGHT")))))
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"));

		assertThat(row()).containsEntry("pickup_point", "Gate 2, Tech Park")
				.containsEntry("transport_version", 1L)
				.containsEntry("hr_version", 4L);
	}

	@Test
	void unknownEmployeeIs404() throws Exception {
		mvc.perform(patch("/api/v1/employees/E999999/transport")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN"))
						.header(HttpHeaders.IF_MATCH, "\"0\"")
						.contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"Gate 2\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void onlyAdminsMayEditTransportFields() throws Exception {
		patchAs("EMPLOYEE", "\"0\"", "{\"pickupPoint\":\"Gate 2\"}").andExpect(status().isForbidden());
		patchAs("HR_INTEGRATION", "\"0\"", "{\"pickupPoint\":\"Gate 2\"}").andExpect(status().isForbidden());
		patchAs("OPERATOR", "\"0\"", "{\"pickupPoint\":\"Gate 2\"}").andExpect(status().isForbidden());
		assertThat(row()).containsEntry("transport_version", 0L);
	}
}
