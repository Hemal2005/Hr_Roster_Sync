package com.moveinsync.rostersync.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base for tests that need the full application against a real Postgres. Spring caches
 * the context between test classes with the same configuration, so the container
 * starts once per run; tables are emptied before every test instead.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
public abstract class IntegrationTest {

	@Autowired
	protected JdbcTemplate jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.execute("TRUNCATE employee_event_log, employee, office, tenant");
	}

	protected void givenTenant(String tenantId, String... officeCodes) {
		jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenantId, tenantId);
		for (String code : officeCodes) {
			jdbc.update("INSERT INTO office (tenant_id, code) VALUES (?, ?)", tenantId, code);
		}
	}
}
