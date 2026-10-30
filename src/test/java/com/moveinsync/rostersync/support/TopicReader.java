package com.moveinsync.rostersync.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Reads a topic from the beginning with a fresh consumer group, for asserting what the
 * relay actually put on the broker.
 */
public final class TopicReader implements AutoCloseable {

	private final Consumer<String, String> consumer;
	private final List<ConsumerRecord<String, String>> records = new ArrayList<>();

	@SuppressWarnings("unchecked")
	public TopicReader(ConsumerFactory<?, ?> consumers, String topic) {
		this.consumer = (Consumer<String, String>) consumers.createConsumer("topic-reader-" + UUID.randomUUID(), null);
		this.consumer.subscribe(List.of(topic));
	}

	/** Polls until {@code done} holds for the records read so far, or the timeout passes. */
	public List<ConsumerRecord<String, String>> readUntil(Predicate<List<ConsumerRecord<String, String>>> done,
			Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		while (!done.test(records) && Instant.now().isBefore(deadline)) {
			consumer.poll(Duration.ofMillis(200)).forEach(records::add);
		}
		return List.copyOf(records);
	}

	@Override
	public void close() {
		consumer.close();
	}
}
