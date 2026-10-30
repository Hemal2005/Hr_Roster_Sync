package com.moveinsync.rostersync.application;

import java.util.List;

/**
 * The relay's view of the outbox. All methods run inside the relay's transaction.
 */
public interface PendingOutbox {

	/**
	 * Tries to become the only active relay until the transaction ends. Locked rows are
	 * skipped by other relays, but skipping alone could let one relay publish an
	 * employee's later event while another is still retrying an earlier one.
	 */
	boolean tryBecomeActiveRelay();

	/** Oldest unpublished rows first, locked until the transaction ends. */
	List<OutboxMessage> lockNextBatch(int limit);

	void markPublished(long id);

	void markFailed(long id, String errorCode);
}
