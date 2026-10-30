package com.moveinsync.rostersync.application;

/**
 * Sends one change event to the broker and returns only once the broker has
 * acknowledged it. Any failure is thrown, so the caller can leave the row unpublished.
 */
@FunctionalInterface
public interface ChangeEventPublisher {

	void publish(OutboxMessage message);
}
