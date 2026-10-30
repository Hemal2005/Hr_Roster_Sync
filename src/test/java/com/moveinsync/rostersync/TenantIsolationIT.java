package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestEvents.update;
import static com.moveinsync.rostersync.support.TestRecords.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * One client can never read or change another client's data. Another tenant's employee
 * is answered exactly like an employee that does not exist, so not even its existence
 * leaks.
 */
class TenantIsolationIT extends ApiTest {

	@Autowired
	ApplyService applyService;

	@BeforeEach
	void twoTenants() {
		givenTenant("acme", "BLR-01");
		givenTenant("globex", "BLR-01", "PUN-01");
		applyService.apply(new TenantId("globex"), "sap", create("E000042", 5, "PUN-01", "NIGHT"));
		applyService.apply(new TenantId("acme"), "workday", create("E000043", 1, "BLR-01", "DAY"));
	}

	private Map<String, Object> globexRow() {
		return jdbc.queryForMap("SELECT hr_version, office_code, pickup_point, transport_version FROM employee "
				+ "WHERE tenant_id = 'globex' AND employee_id = 'E000042'");
	}

	private String readAsAcmeAdmin(String employeeId) throws Exception {
		return mvc.perform(get("/api/v1/employees/" + employeeId)
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	void anotherTenantsEmployeeIs404() throws Exception {
		readAsAcmeAdmin("E000042");
	}

	@Test
	void anotherTenantsEmployeeLooksExactlyLikeAMissingOne() throws Exception {
		ObjectNode otherTenant = (ObjectNode) json.readTree(readAsAcmeAdmin("E000042"));
		ObjectNode missing = (ObjectNode) json.readTree(readAsAcmeAdmin("E999999"));
		otherTenant.remove("traceId");
		missing.remove("traceId");
		otherTenant.remove("instance");
		missing.remove("instance");

		assertThat(otherTenant).isEqualTo(missing);
	}

	@Test
	void anotherTenantsEmployeeCannotBeEdited() throws Exception {
		mvc.perform(patch("/api/v1/employees/E000042/transport")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN"))
						.header(HttpHeaders.IF_MATCH, "\"0\"")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"pickupPoint\":\"Acme gate\"}"))
				.andExpect(status().isNotFound());

		assertThat(globexRow()).containsEntry("pickup_point", null).containsEntry("transport_version", 0L);
	}

	@Test
	void anEmployeeTokenOfOneTenantCannotReachTheSameIdInAnother() throws Exception {
		mvc.perform(get("/api/v1/employees/me")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "E000042", "EMPLOYEE")))
				.andExpect(status().isNotFound());
	}

	@Test
	void hrFeedOfOneTenantNeverTouchesAnotherTenantsEmployee() throws Exception {
		mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday", "HR_INTEGRATION"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(batch(List.of(update("E000042", 9, "BLR-01", "DAY")))))
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"));

		assertThat(globexRow()).containsEntry("hr_version", 5L).containsEntry("office_code", "PUN-01");
		assertThat(jdbc.queryForObject(
				"SELECT hr_version FROM employee WHERE tenant_id = 'acme' AND employee_id = 'E000042'", Long.class))
				.isEqualTo(9L);
	}

	@Test
	void anotherTenantsOfficeIsUnknown() throws Exception {
		mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday", "HR_INTEGRATION"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(batch(List.of(update("E000044", 1, "PUN-01", "DAY")))))
				.andExpect(jsonPath("$.results[0].errorCode").value("UNKNOWN_OFFICE"));
	}
}
