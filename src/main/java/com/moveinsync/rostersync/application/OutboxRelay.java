package com.moveinsync.rostersync.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 * <p>
 * To avoid one broker round trip per event, a batch is cut into segments in which no key
 * repeats: a segment is sent all at once, then its acknowledgements are awaited in order.
 * An employee never has two events in flight, so a failed event can never be overtaken
 * by a later one for the same employee.
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
	private final Counter published;
	private final Counter failures;

	public OutboxRelay(PendingOutbox outbox, ChangeEventPublisher publisher, TransactionTemplate tx,
			@Value("${rostersync.outbox.batch-size:100}") int batchSize, MeterRegistry meters) {
		this.outbox = outbox;
		this.publisher = publisher;
		this.tx = tx;
		this.batchSize = batchSize;
		this.published = Counter.builder("outbox.published")
				.description("Change events acknowledged by the broker")
				.register(meters);
		this.failures = Counter.builder("outbox.publish.failures")
				.description("Publish attempts that failed; the batch stopped and will be retried")
				.register(meters);
	}

	public RelayResult relayOnce() {
		return tx.execute(status -> {
			if (!outbox.tryBecomeActiveRelay()) {
				return new RelayResult(0, false, true);
			}
			List<OutboxMessage> batch = outbox.lockNextBatch(batchSize);
			int published = 0;
			int start = 0;
			while (start < batch.size()) {
				int end = segmentEnd(batch, start);
				List<CompletableFuture<Void>> acks = new ArrayList<>(end - start);
				for (int i = start; i < end; i++) {
					acks.add(publisher.publishAsync(batch.get(i)));
				}
				for (int i = start; i < end; i++) {
					OutboxMessage message = batch.get(i);
					try {
						acks.get(i - start).join();
					} catch (CompletionException | CancellationException e) {
						String code = errorCode(e);
						outbox.markFailed(message.id(), code);
						failures.increment();
						MDC.put("eventId", message.eventId().toString());
						log.warn("outbox publish failed id={} code={}; batch stopped", message.id(), code);
						MDC.remove("eventId");
						return new RelayResult(published, true, false);
					}
					outbox.markPublished(message.id());
					this.published.increment();
					published++;
				}
				start = end;
			}
			return new RelayResult(published, false, false);
		});
	}

	/** End (exclusive) of the longest run from {@code start} in which no key repeats. */
	private static int segmentEnd(List<OutboxMessage> batch, int start) {
		Set<String> keys = new HashSet<>();
		int end = start;
		while (end < batch.size() && keys.add(batch.get(end).key())) {
			end++;
		}
		return end;
	}

	private static String errorCode(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String name = root.getClass().getSimpleName();
		return name.length() > 64 ? name.substring(0, 64) : name;
	}
}
