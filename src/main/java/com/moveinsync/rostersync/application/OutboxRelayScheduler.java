package com.moveinsync.rostersync.application;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the relay every poll interval, draining full batches immediately. After a failed
 * publish it backs off exponentially (up to the maximum) so a broker outage does not turn
 * into a tight retry loop. Off in tests, which call the relay directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "rostersync.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

	private final OutboxRelay relay;
	private final int batchSize;
	private final Duration maxBackoff;
	private int consecutiveFailures;
	private Instant nextAttempt = Instant.EPOCH;

	OutboxRelayScheduler(OutboxRelay relay, @Value("${rostersync.outbox.batch-size:100}") int batchSize,
			@Value("${rostersync.outbox.max-backoff-ms:10000}") long maxBackoffMs) {
		this.relay = relay;
		this.batchSize = batchSize;
		this.maxBackoff = Duration.ofMillis(maxBackoffMs);
	}

	@Scheduled(fixedDelayString = "${rostersync.outbox.poll-interval-ms:200}")
	void tick() {
		if (Instant.now().isBefore(nextAttempt)) {
			return;
		}
		OutboxRelay.RelayResult result;
		do {
			result = relay.relayOnce();
		} while (result.published() == batchSize && !result.stoppedOnFailure());

		if (result.stoppedOnFailure()) {
			consecutiveFailures = Math.min(consecutiveFailures + 1, 16);
			long delayMs = Math.min(maxBackoff.toMillis(), 200L << (consecutiveFailures - 1));
			nextAttempt = Instant.now().plusMillis(delayMs);
		} else {
			consecutiveFailures = 0;
		}
	}
}
