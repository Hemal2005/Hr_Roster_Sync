package com.moveinsync.rostersync.application;

import java.util.concurrent.CompletableFuture;

/**
 * Sends one change event to the broker and returns only once the broker has
 * acknowledged it. Any failure is thrown, so the caller can leave the row unpublished.
 */
@FunctionalInterface
public interface ChangeEventPublisher {

	void publish(OutboxMessage message);

	/**
	 * Starts sending and completes when the broker has acknowledged the event, or
	 * exceptionally when it has not. By default this publishes synchronously; a broker
	 * client overrides it so that several events can be in flight at once.
	 */
	default CompletableFuture<Void> publishAsync(OutboxMessage message) {
		try {
			publish(message);
			return CompletableFuture.completedFuture(null);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}
}
