package com.moveinsync.rostersync.infrastructure.messaging;

import com.moveinsync.rostersync.application.DeactivationHandler;
import com.moveinsync.rostersync.domain.ChangeEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes employee change events for the booking side effects. Offsets are committed
 * after the handler returns, so a crash mid-handling means a redelivery, which the
 * handler recognises by event id.
 */
@Component
class EmployeeChangeListener {

	private static final Logger log = LoggerFactory.getLogger(EmployeeChangeListener.class);

	private final ChangeEventJson changeEventJson;
	private final DeactivationHandler deactivations;

	EmployeeChangeListener(ChangeEventJson changeEventJson, DeactivationHandler deactivations) {
		this.changeEventJson = changeEventJson;
		this.deactivations = deactivations;
	}

	@KafkaListener(topics = "${rostersync.outbox.topic}", groupId = "booking-cancellation")
	void onEmployeeChange(ConsumerRecord<String, String> record) {
		ChangeEvent event;
		try {
			event = changeEventJson.read(record.value());
		} catch (RuntimeException e) {
			// Retrying cannot fix an unreadable message; skip it and leave a trace.
			log.warn("unreadable change event skipped partition={} offset={}", record.partition(), record.offset());
			return;
		}
		deactivations.handle(event);
	}
}
