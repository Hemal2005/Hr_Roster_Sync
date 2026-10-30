package com.moveinsync.rostersync.application;

import java.util.UUID;

/**
 * Remembers which events a consumer has already acted on.
 */
public interface ProcessedMessages {

	/**
	 * Records the event for this consumer. Returns false if it was already recorded, in
	 * which case the side effect has already happened and must not run again.
	 */
	boolean markProcessed(String consumer, UUID eventId);
}
