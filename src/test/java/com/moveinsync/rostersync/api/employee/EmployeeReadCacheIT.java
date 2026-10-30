package com.moveinsync.rostersync.api.employee;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.EmployeeReadCache;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The employee read cache as clients see it: this instance's own changes show at once;
 * a change made behind its back shows once the entry is gone (at most 30 seconds).
 */
class EmployeeReadCacheIT extends ApiTest {

	private static final TenantId ACME = new TenantId("acme");

	@Autowired
	ApplyService applyService;

	@Autowired
	EmployeeReadCache cache;

	@BeforeEach
	void employee() {
		givenTenant("acme", "BLR-01", "HYD-02");
		applyService.apply(ACME, "workday", create("E000042", 1, "BLR-01", "DAY"));
	}

	private ResultActions read() throws Exception {
		return mvc.perform(get("/api/v1/employees/E000042")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN")));
	}

	@Test
	void anHrChangeIsVisibleOnTheNextRead() throws Exception {
		read().andExpect(jsonPath("$.officeCode").value("BLR-01"));

		applyService.apply(ACME, "workday", update("E000042", 2, "HYD-02", "DAY"));

		read().andExpect(jsonPath("$.officeCode").value("HYD-02")).andExpect(jsonPath("$.hrVersion").value(2));
	}

	@Test
	void aTransportEditIsVisibleOnTheNextReadWithTheNewETag() throws Exception {
		read().andExpect(header().string(HttpHeaders.ETAG, "\"0\""));

		mvc.perform(patch("/api/v1/employees/E000042/transport")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN"))
				.header(HttpHeaders.IF_MATCH, "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"pickupPoint\":\"Gate 2\"}"))
				.andExpect(status().isOk());

		read().andExpect(header().string(HttpHeaders.ETAG, "\"1\"")).andExpect(jsonPath("$.pickupPoint").value("Gate 2"));
	}

	@Test
	void aChangeMadeElsewhereShowsOnlyOnceTheEntryIsGone() throws Exception {
		read().andExpect(jsonPath("$.shiftCode").value("DAY"));
		// Another instance (or a manual fix) changes the row; this instance has not heard.
		jdbc.update("UPDATE employee SET shift_code = 'NIGHT' WHERE tenant_id = 'acme' AND employee_id = 'E000042'");

		read().andExpect(jsonPath("$.shiftCode").value("DAY"));
		cache.invalidateAll();
		read().andExpect(jsonPath("$.shiftCode").value("NIGHT"));
	}

	@Test
	void aStaleETagFromTheCacheCannotOverwriteANewerTransportEdit() throws Exception {
		read().andExpect(header().string(HttpHeaders.ETAG, "\"0\""));
		jdbc.update("UPDATE employee SET transport_version = 1, pickup_point = 'Gate 9' "
				+ "WHERE tenant_id = 'acme' AND employee_id = 'E000042'");

		mvc.perform(patch("/api/v1/employees/E000042/transport")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN"))
				.header(HttpHeaders.IF_MATCH, "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"pickupPoint\":\"Gate 2\"}"))
				.andExpect(status().isPreconditionFailed());
	}
}
