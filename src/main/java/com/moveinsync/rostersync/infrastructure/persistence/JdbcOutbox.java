package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.ChangeEvent;
import com.moveinsync.rostersync.domain.Outbox;
import com.moveinsync.rostersync.infrastructure.messaging.ChangeEventJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class JdbcOutbox implements Outbox {

	private final JdbcTemplate jdbc;
	private final ChangeEventJson changeEventJson;

	JdbcOutbox(JdbcTemplate jdbc, ChangeEventJson changeEventJson) {
		this.jdbc = jdbc;
		this.changeEventJson = changeEventJson;
	}

	@Override
	public void enqueue(ChangeEvent event) {
		// An outbox row committed on its own, apart from the change it describes, is exactly
		// the inconsistency the outbox exists to prevent.
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("outbox rows must be written in the change's transaction");
		}
		jdbc.update("""
				INSERT INTO outbox (event_id, tenant_id, aggregate_id, event_type, payload)
				VALUES (?, ?, ?, ?, ?::jsonb)
				""", event.eventId(), event.tenantId().value(), event.employeeId().value(), event.type().name(),
				changeEventJson.write(event));
	}
}
