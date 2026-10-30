package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Office codes of one tenant, read straight from the database. An inactive office is
 * treated as unknown so new events cannot route employees to it.
 */
@Repository
class JdbcOfficeDirectory implements OfficeDirectory {

	private final JdbcTemplate jdbc;

	JdbcOfficeDirectory(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean isKnownOffice(TenantId tenantId, String officeCode) {
		Boolean known = jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM office WHERE tenant_id = ? AND code = ? AND active)",
				Boolean.class, tenantId.value(), officeCode);
		return Boolean.TRUE.equals(known);
	}
}
