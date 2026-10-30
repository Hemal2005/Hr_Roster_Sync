package com.moveinsync.rostersync.application;

import java.util.UUID;

/**
 * One outbox row ready to be sent: {@code key} keeps an employee's events in order on the
 * broker, {@code payload} is the change event JSON.
 */
public record OutboxMessage(long id, UUID eventId, String key, String eventType, String payload) {

	@Override
	public String toString() {
		return "OutboxMessage[id=" + id + ", eventId=" + eventId + ", eventType=" + eventType + "]";
	}
}
