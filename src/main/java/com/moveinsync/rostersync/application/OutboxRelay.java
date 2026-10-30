package com.moveinsync.rostersync.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows in id order. A row is stamped published only after the broker
 * acknowledged it, so a crash at any point leads to a resend, never a loss: delivery is
 * at least once and consumers drop duplicates by event id. The batch stops at the first
 * failure so that no later event for the same employee overtakes an earlier one.
 */
@Component
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	public record RelayResult(int published, boolean stoppedOnFailure, boolean otherRelayActive) {
	}

	private final PendingOutbox outbox;
	private final ChangeEventPublisher publisher;
	private final TransactionTemplate tx;
	private final int batchSize;

	public OutboxRelay(PendingOutbox outbox, ChangeEventPublisher publisher, TransactionTemplate tx,
			@Value("${rostersync.outbox.batch-size:100}") int batchSize) {
		this.outbox = outbox;
		this.publisher = publisher;
		this.tx = tx;
		this.batchSize = batchSize;
	}

	public RelayResult relayOnce() {
		return tx.execute(status -> {
			if (!outbox.tryBecomeActiveRelay()) {
				return new RelayResult(0, false, true);
			}
			List<OutboxMessage> batch = outbox.lockNextBatch(batchSize);
			int published = 0;
			for (OutboxMessage message : batch) {
				try {
					publisher.publish(message);
				} catch (RuntimeException e) {
					String code = errorCode(e);
					outbox.markFailed(message.id(), code);
					MDC.put("eventId", message.eventId().toString());
					log.warn("outbox publish failed id={} code={}; batch stopped", message.id(), code);
					MDC.remove("eventId");
					return new RelayResult(published, true, false);
				}
				outbox.markPublished(message.id());
				published++;
			}
			return new RelayResult(published, false, false);
		});
	}

	private static String errorCode(RuntimeException e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String name = root.getClass().getSimpleName();
		return name.length() > 64 ? name.substring(0, 64) : name;
	}
}
