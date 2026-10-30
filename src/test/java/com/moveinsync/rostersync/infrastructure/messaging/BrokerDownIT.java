package com.moveinsync.rostersync.infrastructure.messaging;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.ApplyOutcome;
import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.ChangeEventPublisher;
import com.moveinsync.rostersync.application.OutboxRelay;
import com.moveinsync.rostersync.application.PendingOutbox;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.TopicReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The broker is unreachable for a while. HR updates keep being accepted, their events
 * pile up in the outbox without being lost, and once the broker is back the backlog
 * drains in order.
 */
class BrokerDownIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");

	@Autowired
	ApplyService applyService;

	@Autowired
	PendingOutbox pendingOutbox;

	@Autowired
	OutboxRelay relay;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	ConsumerFactory<?, ?> consumers;

	@Value("${rostersync.outbox.topic}")
	String topic;

	private DefaultKafkaProducerFactory<String, String> unreachable;
	private OutboxRelay relayDuringOutage;

	@BeforeEach
	void brokerOutage() {
		givenTenant("acme", "BLR-01", "HYD-02");
		// Nothing listens on port 1: every send times out like a broker that is down.
		unreachable = new DefaultKafkaProducerFactory<>(Map.of(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
				ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
				ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
				ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000));
		ChangeEventPublisher downBroker = new KafkaChangeEventPublisher(new KafkaTemplate<>(unreachable), topic, 2000);
		relayDuringOutage = new OutboxRelay(pendingOutbox, downBroker, tx, 100, new SimpleMeterRegistry());
	}

	@AfterEach
	void closeProducer() {
		unreachable.destroy();
	}

	private int backlog() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class);
	}

	@Test
	void outboxBacksUpWhileTheBrokerIsDownAndDrainsInOrderAfterwards() {
		for (int i = 1; i <= 5; i++) {
			applyService.apply(ACME, "workday", create("E%06d".formatted(i), 1, "BLR-01", "DAY"));
		}

		for (int tick = 0; tick < 3; tick++) {
			var result = relayDuringOutage.relayOnce();
			assertThat(result.published()).isZero();
			assertThat(result.stoppedOnFailure()).isTrue();
		}

		// Ingest does not depend on the broker: updates are still applied during the outage.
		var duringOutage = applyService.apply(ACME, "workday", update("E000001", 2, "HYD-02", "DAY"));
		applyService.apply(ACME, "workday", update("E000001", 3, "BLR-01", "NIGHT"));
		assertThat(duringOutage.outcome()).isEqualTo(ApplyOutcome.APPLIED);
		assertThat(backlog()).isEqualTo(7);

		Map<String, Object> head = jdbc.queryForMap(
				"SELECT attempts, last_error_code FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 1");
		assertThat(head.get("attempts")).isEqualTo(3);
		assertThat((String) head.get("last_error_code")).isNotBlank();
		assertThat(jdbc.queryForObject("SELECT max(attempts) FROM outbox WHERE id > (SELECT min(id) FROM outbox)",
				Integer.class)).as("only the head of the queue was tried").isZero();

		Set<String> ids = Set.copyOf(jdbc.queryForList("SELECT event_id::text FROM outbox", String.class));

		var recovered = relay.relayOnce();

		assertThat(recovered.published()).isEqualTo(7);
		assertThat(backlog()).isZero();
		List<ConsumerRecord<String, String>> ours;
		try (TopicReader reader = new TopicReader(consumers, topic)) {
			ours = reader.readUntil(records -> records.stream().filter(r -> ids.contains(eventId(r))).count() >= 7,
					Duration.ofSeconds(30)).stream().filter(r -> ids.contains(eventId(r))).toList();
		}
		assertThat(ours).hasSize(7);
		assertThat(ours.stream().filter(r -> r.key().equals("acme:E000001"))
				.map(r -> new String(r.headers().lastHeader("eventType").value())))
				.containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_UPDATED", "EMPLOYEE_UPDATED");
	}

	private static String eventId(ConsumerRecord<String, String> record) {
		var header = record.headers().lastHeader(KafkaChangeEventPublisher.EVENT_ID_HEADER);
		return header == null ? "" : new String(header.value());
	}
}
