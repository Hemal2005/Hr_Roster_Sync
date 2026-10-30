package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.application.reconciliation.PlanSummary;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationRun;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore;
import com.moveinsync.rostersync.application.reconciliation.RunStatus;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction;
import com.moveinsync.rostersync.domain.reconciliation.StoredEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReconciliationStore implements ReconciliationStore {

	private final JdbcTemplate jdbc;

	JdbcReconciliationStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void createRun(UUID runId, TenantId tenantId, String fileName, Instant asOf, String submittedBy) {
		jdbc.update("""
				INSERT INTO reconciliation_run (id, tenant_id, file_name, as_of, status, submitted_by)
				VALUES (?, ?, ?, ?, 'RUNNING', ?)
				""", runId, tenantId.value(), fileName, Timestamp.from(asOf), submittedBy);
	}

	@Override
	public Optional<ReconciliationRun> find(TenantId tenantId, UUID runId) {
		return jdbc.query("SELECT * FROM reconciliation_run WHERE tenant_id = ? AND id = ?",
				(rs, row) -> toRun(rs), tenantId.value(), runId).stream().findFirst();
	}

	@Override
	public List<StoredEntry> storedEntries(TenantId tenantId, int bucket) {
		return jdbc.query("""
				SELECT employee_id, hr_version, encode(hr_payload_hash, 'hex') AS hash, status, last_hr_event_at
				FROM employee
				WHERE tenant_id = ? AND bucket = ?
				""", (rs, row) -> new StoredEntry(
				rs.getString("employee_id"),
				rs.getLong("hr_version"),
				rs.getString("hash"),
				EmployeeStatus.valueOf(rs.getString("status")),
				rs.getObject("last_hr_event_at", OffsetDateTime.class).toInstant()), tenantId.value(), bucket);
	}

	@Override
	public void addItems(UUID runId, List<NewItem> items) {
		if (items.isEmpty()) {
			return;
		}
		jdbc.batchUpdate("""
				INSERT INTO reconciliation_item (run_id, employee_id, action, row_version, payload)
				VALUES (?, ?, ?, ?, ?::jsonb)
				""", items, items.size(), (ps, item) -> {
			ps.setObject(1, runId);
			ps.setString(2, item.item().employeeId());
			ps.setString(3, item.item().action().name());
			ps.setLong(4, item.item().version());
			ps.setString(5, item.payload());
		});
	}

	@Override
	public void addErrors(UUID runId, List<RunError> errors) {
		if (errors.isEmpty()) {
			return;
		}
		jdbc.batchUpdate("""
				INSERT INTO reconciliation_error (run_id, line_no, employee_id, error_code) VALUES (?, ?, ?, ?)
				""", errors, errors.size(), (ps, error) -> {
			ps.setObject(1, runId);
			ps.setLong(2, error.lineNo());
			ps.setString(3, error.employeeId());
			ps.setString(4, error.code().name());
		});
	}

	@Override
	public void recordPlan(UUID runId, PlanSummary s) {
		jdbc.update("""
				UPDATE reconciliation_run
				SET status = 'PLANNED', rows_read = ?, rows_invalid = ?, to_create = ?, to_update = ?,
				    to_deactivate = ?, conflicts = ?, active_before = ?, trailer_count = ?, trailer_ok = ?,
				    planned_at = now()
				WHERE id = ? AND status = 'RUNNING'
				""", s.rowsRead(), s.rowsInvalid(), s.toCreate(), s.toUpdate(), s.toDeactivate(), s.conflicts(),
				s.activeBefore(), s.trailerCount(), s.trailerOk(), runId);
	}

	@Override
	public boolean transition(UUID runId, RunStatus from, RunStatus to) {
		return jdbc.update("UPDATE reconciliation_run SET status = ? WHERE id = ? AND status = ?",
				to.name(), runId, from.name()) == 1;
	}

	@Override
	public void fail(UUID runId, String failureCode) {
		jdbc.update("UPDATE reconciliation_run SET status = 'FAILED', failure_code = ?, completed_at = now() "
				+ "WHERE id = ?", failureCode, runId);
	}

	@Override
	public List<StoredItem> items(UUID runId, long afterId, int limit) {
		return jdbc.query("""
				SELECT id, action, employee_id, row_version, payload::text AS payload
				FROM reconciliation_item
				WHERE run_id = ? AND id > ?
				ORDER BY id
				LIMIT ?
				""", (rs, row) -> new StoredItem(
				rs.getLong("id"),
				ReconciliationAction.valueOf(rs.getString("action")),
				rs.getString("employee_id"),
				rs.getLong("row_version"),
				rs.getString("payload")), runId, afterId, limit);
	}

	@Override
	public List<RunError> errors(TenantId tenantId, UUID runId, int page, int pageSize) {
		return jdbc.query("""
				SELECT e.line_no, e.employee_id, e.error_code
				FROM reconciliation_error e JOIN reconciliation_run r ON r.id = e.run_id
				WHERE r.tenant_id = ? AND e.run_id = ?
				ORDER BY e.line_no, e.id
				LIMIT ? OFFSET ?
				""", (rs, row) -> new RunError(rs.getLong("line_no"), rs.getString("employee_id"),
				ErrorCode.valueOf(rs.getString("error_code"))), tenantId.value(), runId, pageSize,
				(long) page * pageSize);
	}

	private static ReconciliationRun toRun(ResultSet rs) throws SQLException {
		return new ReconciliationRun(
				rs.getObject("id", UUID.class),
				rs.getString("tenant_id"),
				rs.getString("file_name"),
				instant(rs, "as_of"),
				RunStatus.valueOf(rs.getString("status")),
				rs.getString("submitted_by"),
				rs.getLong("rows_read"),
				rs.getLong("rows_invalid"),
				rs.getLong("to_create"),
				rs.getLong("to_update"),
				rs.getLong("to_deactivate"),
				rs.getLong("conflicts"),
				rs.getLong("active_before"),
				rs.getBigDecimal("missing_ratio"),
				(Long) rs.getObject("trailer_count", Long.class),
				(Boolean) rs.getObject("trailer_ok", Boolean.class),
				(Boolean) rs.getObject("brake_tripped", Boolean.class),
				rs.getString("brake_reason"),
				rs.getLong("applied"),
				rs.getLong("skipped"),
				rs.getString("decided_by"),
				rs.getString("failure_code"),
				instant(rs, "created_at"),
				instant(rs, "planned_at"),
				instant(rs, "decided_at"),
				instant(rs, "completed_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
