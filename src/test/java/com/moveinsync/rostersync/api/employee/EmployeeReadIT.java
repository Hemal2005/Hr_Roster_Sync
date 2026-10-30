package com.moveinsync.rostersync.api.employee;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

class EmployeeReadIT extends ApiTest {

	@Autowired
	ApplyService applyService;

	@BeforeEach
	void employees() {
		givenTenant("acme", "BLR-01");
		applyService.apply(new TenantId("acme"), "workday", create("E000042", 3, "BLR-01", "NIGHT"));
		applyService.apply(new TenantId("acme"), "workday", create("E000043", 1, "BLR-01", "DAY"));
	}

	private ResultActions getAs(String path, String subject, String role) throws Exception {
		return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("acme", subject, role)));
	}

	@Test
	void adminReadsAnEmployeeWithTheTransportVersionAsETag() throws Exception {
		getAs("/api/v1/employees/E000042", "admin-1", "ADMIN")
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
				.andExpect(jsonPath("$.employeeId").value("E000042"))
				.andExpect(jsonPath("$.hrVersion").value(3))
				.andExpect(jsonPath("$.shiftCode").value("NIGHT"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.transportVersion").value(0));
	}

	@Test
	void unknownEmployeeIs404() throws Exception {
		getAs("/api/v1/employees/E999999", "admin-1", "ADMIN")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void employeeReadsTheirOwnRecordThroughMe() throws Exception {
		getAs("/api/v1/employees/me", "E000043", "EMPLOYEE")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.employeeId").value("E000043"));
	}

	@Test
	void employeeWithoutARecordGets404OnMe() throws Exception {
		getAs("/api/v1/employees/me", "E777777", "EMPLOYEE").andExpect(status().isNotFound());
	}

	@Test
	void employeeCannotReadAnotherEmployeeById() throws Exception {
		getAs("/api/v1/employees/E000042", "E000043", "EMPLOYEE")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void hrIntegrationAndOperatorCannotReadEmployees() throws Exception {
		getAs("/api/v1/employees/E000042", "workday", "HR_INTEGRATION").andExpect(status().isForbidden());
		getAs("/api/v1/employees/E000042", "op-1", "OPERATOR").andExpect(status().isForbidden());
	}

	@Test
	void adminHasNoOwnRecordEndpoint() throws Exception {
		getAs("/api/v1/employees/me", "admin-1", "ADMIN").andExpect(status().isForbidden());
	}
}
