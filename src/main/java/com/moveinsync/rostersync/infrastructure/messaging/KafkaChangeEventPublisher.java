package com.moveinsync.rostersync.infrastructure.messaging;

import com.moveinsync.rostersync.application.ChangeEventPublisher;
import com.moveinsync.rostersync.application.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends a change event keyed by tenant and employee, and blocks until the broker has
 * acknowledged it (acks=all), so the relay only marks rows the broker really has.
 */
@Component
class KafkaChangeEventPublisher implements ChangeEventPublisher {

	static final String EVENT_ID_HEADER = "eventId";
	static final String EVENT_TYPE_HEADER = "eventType";

	private final KafkaTemplate<String, String> kafka;
	private final String topic;
	private final long ackTimeoutMs;

	KafkaChangeEventPublisher(KafkaTemplate<String, String> kafka,
			@Value("${rostersync.outbox.topic}") String topic,
			@Value("${rostersync.outbox.ack-timeout-ms:15000}") long ackTimeoutMs) {
		this.kafka = kafka;
		this.topic = topic;
		this.ackTimeoutMs = ackTimeoutMs;
	}

	@Override
	public void publish(OutboxMessage message) {
		ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.key(), message.payload());
		record.headers().add(EVENT_ID_HEADER, message.eventId().toString().getBytes(StandardCharsets.UTF_8));
		record.headers().add(EVENT_TYPE_HEADER, message.eventType().getBytes(StandardCharsets.UTF_8));
		try {
			kafka.send(record).get(ackTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for broker acknowledgement", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("broker rejected the event", e.getCause());
		} catch (TimeoutException e) {
			throw new IllegalStateException("no broker acknowledgement in time", e);
		}
	}
}
