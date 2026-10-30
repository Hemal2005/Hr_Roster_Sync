package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.AppliedEventLog;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAppliedEventLog implements AppliedEventLog {

	private final JdbcTemplate jdbc;

	JdbcAppliedEventLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void recordApplied(TenantId tenantId, String sourceSystem, EmployeeId employeeId, long version) {
		jdbc.update("""
				INSERT INTO employee_event_log (tenant_id, source_system, employee_id, version, outcome)
				VALUES (?, ?, ?, ?, 'APPLIED')
				""", tenantId.value(), sourceSystem, employeeId.value(), version);
	}
}
