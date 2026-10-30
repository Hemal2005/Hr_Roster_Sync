package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.application.ProcessedMessages;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProcessedMessages implements ProcessedMessages {

	private final JdbcTemplate jdbc;

	JdbcProcessedMessages(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean markProcessed(String consumer, UUID eventId) {
		// A concurrent delivery of the same event waits on the primary key here, then finds
		// the row and inserts nothing, so only one of them performs the side effect.
		return jdbc.update("""
				INSERT INTO processed_message (consumer, event_id) VALUES (?, ?)
				ON CONFLICT DO NOTHING
				""", consumer, eventId) == 1;
	}
}
