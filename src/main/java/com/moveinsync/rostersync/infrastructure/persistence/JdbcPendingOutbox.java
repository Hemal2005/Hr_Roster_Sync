package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.application.OutboxMessage;
import com.moveinsync.rostersync.application.PendingOutbox;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPendingOutbox implements PendingOutbox {

	/** Arbitrary constant naming the "active relay" advisory lock. */
	static final long RELAY_LOCK_KEY = 7_331_001L;

	private final JdbcTemplate jdbc;

	JdbcPendingOutbox(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean tryBecomeActiveRelay() {
		// Transaction-scoped: released automatically at commit or rollback.
		return Boolean.TRUE.equals(
				jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RELAY_LOCK_KEY));
	}

	@Override
	public List<OutboxMessage> lockNextBatch(int limit) {
		return jdbc.query("""
				SELECT id, event_id, tenant_id, aggregate_id, event_type, payload::text AS payload
				FROM outbox
				WHERE published_at IS NULL
				ORDER BY id
				LIMIT ?
				FOR UPDATE SKIP LOCKED
				""", (rs, row) -> new OutboxMessage(
				rs.getLong("id"),
				rs.getObject("event_id", UUID.class),
				rs.getString("tenant_id") + ":" + rs.getString("aggregate_id"),
				rs.getString("event_type"),
				rs.getString("payload")), limit);
	}

	@Override
	public void markPublished(long id) {
		jdbc.update("UPDATE outbox SET published_at = now(), attempts = attempts + 1, last_error_code = NULL "
				+ "WHERE id = ?", id);
	}

	@Override
	public void markFailed(long id, String errorCode) {
		jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error_code = ? WHERE id = ?", errorCode, id);
	}
}
