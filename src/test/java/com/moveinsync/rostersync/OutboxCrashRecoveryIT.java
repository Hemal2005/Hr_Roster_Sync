package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.deactivate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.ChangeEventPublisher;
import com.moveinsync.rostersync.application.OutboxRelay;
import com.moveinsync.rostersync.application.PendingOutbox;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.FaultInjectingPublisher;
import com.moveinsync.rostersync.support.FaultInjectingPublisher.CrashPoint;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.TopicReader;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The process dies between the database write and the event publish. Because the event
 * was committed with the change, a restarted relay still delivers it, and the consumer's
 * effect happens exactly once even when the broker ends up with two copies.
 */
class OutboxCrashRecoveryIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");

	@Autowired
	ApplyService applyService;

	@Autowired
	PendingOutbox pendingOutbox;

	@Autowired
	ChangeEventPublisher kafkaPublisher;

	@Autowired
	OutboxRelay relay;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	ConsumerFactory<?, ?> consumers;

	@Value("${rostersync.outbox.topic}")
	String topic;

	private String exitEventId;

	@BeforeEach
	void employeeWithTwoFutureBookingsWhoLeaves() {
		givenTenant("acme", "BLR-01");
		applyService.apply(ACME, "workday", create("E000042", 1, "BLR-01", "DAY"));
		relay.relayOnce();
		for (String trip : new String[] {"T1", "T2"}) {
			jdbc.update("INSERT INTO trip (tenant_id, id, office_code, pickup_time, capacity, seats_booked) "
					+ "VALUES ('acme', ?, 'BLR-01', ?, 4, 1)", trip, Timestamp.from(Instant.now().plus(Duration.ofDays(1))));
			jdbc.update("INSERT INTO booking (tenant_id, id, trip_id, employee_id, status) "
					+ "VALUES ('acme', ?, ?, 'E000042', 'BOOKED')", "B-" + trip, trip);
		}

		applyService.apply(ACME, "workday", deactivate("E000042", 2));
		exitEventId = jdbc.queryForObject(
				"SELECT event_id::text FROM outbox WHERE event_type = 'EMPLOYEE_DEACTIVATED'", String.class);
	}

	private OutboxRelay relayWith(ChangeEventPublisher publisher) {
		return new OutboxRelay(pendingOutbox, publisher, tx, 100);
	}

	private Map<String, Object> sideEffects() {
		return jdbc.queryForMap("""
				SELECT (SELECT count(*) FROM booking WHERE status = 'CANCELLED') AS cancelled,
				       (SELECT sum(seats_booked) FROM trip) AS seats,
				       (SELECT count(*) FROM processed_message) AS processed
				""");
	}

	private boolean exitPublished() {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT published_at IS NOT NULL FROM outbox WHERE event_id = ?::uuid", Boolean.class, exitEventId));
	}

	private long copiesOnTheBroker(int expected) {
		try (TopicReader reader = new TopicReader(consumers, topic)) {
			return reader.readUntil(records -> copies(records) >= expected, Duration.ofSeconds(30)).stream()
					.filter(r -> exitEventId.equals(new String(r.headers().lastHeader("eventId").value())))
					.count();
		}
	}

	private long copies(List<ConsumerRecord<String, String>> records) {
		return records.stream()
				.filter(r -> exitEventId.equals(new String(r.headers().lastHeader("eventId").value())))
				.count();
	}

	private void awaitExactlyOneEffect() {
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				assertThat(sideEffects()).containsEntry("cancelled", 2L).containsEntry("seats", 0L)
						.containsEntry("processed", 1L));
		// Let any second copy reach the consumer, then check nothing moved.
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(sideEffects()).containsEntry("cancelled", 2L).containsEntry("seats", 0L)
						.containsEntry("processed", 1L));
	}

	@Test
	void crashAfterCommitBeforePublishIsDeliveredAfterRestart() {
		var crashed = new FaultInjectingPublisher(kafkaPublisher, CrashPoint.BEFORE_SEND);

		var beforeCrash = relayWith(crashed).relayOnce();

		assertThat(beforeCrash.stoppedOnFailure()).isTrue();
		assertThat(exitPublished()).isFalse();
		assertThat(sideEffects()).containsEntry("cancelled", 0L).containsEntry("seats", 2L);

		var afterRestart = relayWith(kafkaPublisher).relayOnce();

		assertThat(afterRestart.published()).isEqualTo(1);
		assertThat(exitPublished()).isTrue();
		assertThat(copiesOnTheBroker(1)).isEqualTo(1);
		awaitExactlyOneEffect();
	}

	@Test
	void crashAfterPublishBeforeMarkingResendsAndTheConsumerActsOnce() {
		var crashed = new FaultInjectingPublisher(kafkaPublisher, CrashPoint.AFTER_SEND);

		relayWith(crashed).relayOnce();

		assertThat(crashed.crashes()).isEqualTo(1);
		assertThat(exitPublished()).as("the broker has it, but the row was never stamped").isFalse();

		var afterRestart = relayWith(kafkaPublisher).relayOnce();

		assertThat(afterRestart.published()).isEqualTo(1);
		assertThat(copiesOnTheBroker(2)).as("at-least-once: the event was sent twice").isEqualTo(2);
		awaitExactlyOneEffect();
	}
}
