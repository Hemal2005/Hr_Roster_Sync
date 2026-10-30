package com.moveinsync.rostersync.application;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.deactivate;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.IntegrationTest;
import com.moveinsync.rostersync.support.TopicReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");

	@Autowired
	ApplyService applyService;

	@Autowired
	OutboxRelay relay;

	@Autowired
	PendingOutbox pendingOutbox;

	@Autowired
	ChangeEventPublisher kafkaPublisher;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	ConsumerFactory<?, ?> consumers;

	@Value("${rostersync.outbox.topic}")
	String topic;

	@BeforeEach
	void tenant() {
		givenTenant("acme", "BLR-01", "HYD-02");
	}

	private Set<String> eventIds() {
		return Set.copyOf(jdbc.queryForList("SELECT event_id::text FROM outbox", String.class));
	}

	private List<ConsumerRecord<String, String>> readOurEvents(Set<String> ids) {
		try (TopicReader reader = new TopicReader(consumers, topic)) {
			return reader.readUntil(records -> ours(records, ids).size() >= ids.size(), Duration.ofSeconds(30))
					.stream().filter(r -> ids.contains(eventIdOf(r))).toList();
		}
	}

	private static List<ConsumerRecord<String, String>> ours(List<ConsumerRecord<String, String>> records,
			Set<String> ids) {
		return records.stream().filter(r -> ids.contains(eventIdOf(r))).toList();
	}

	private static String eventIdOf(ConsumerRecord<String, String> record) {
		var header = record.headers().lastHeader("eventId");
		return header == null ? "" : new String(header.value());
	}

	private int unpublished() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class);
	}

	@Test
	void publishesPendingEventsInOrderKeyedByEmployeeAndMarksThemPublished() {
		applyService.apply(ACME, "workday", create("E000042", 1, "BLR-01", "DAY"));
		applyService.apply(ACME, "workday", update("E000042", 2, "HYD-02", "DAY"));
		applyService.apply(ACME, "workday", create("E000043", 1, "BLR-01", "DAY"));
		applyService.apply(ACME, "workday", deactivate("E000042", 3));
		Set<String> ids = eventIds();

		var result = relay.relayOnce();

		assertThat(result.published()).isEqualTo(4);
		assertThat(unpublished()).isZero();
		List<ConsumerRecord<String, String>> received = readOurEvents(ids);
		assertThat(received).hasSize(4);
		Map<String, List<String>> typesByKey = received.stream().collect(Collectors.groupingBy(
				ConsumerRecord::key, Collectors.mapping(r -> new String(r.headers().lastHeader("eventType").value()),
						Collectors.toList())));
		assertThat(typesByKey.get("acme:E000042"))
				.containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_UPDATED", "EMPLOYEE_DEACTIVATED");
		assertThat(typesByKey.get("acme:E000043")).containsExactly("EMPLOYEE_CREATED");
	}

	@Test
	void nothingPendingPublishesNothing() {
		assertThat(relay.relayOnce().published()).isZero();
	}

	@Test
	void stopsAtTheFirstFailureAndKeepsLaterEventsForTheNextRun() {
		applyService.apply(ACME, "workday", create("E000042", 1, "BLR-01", "DAY"));
		applyService.apply(ACME, "workday", update("E000042", 2, "HYD-02", "DAY"));
		applyService.apply(ACME, "workday", update("E000042", 3, "BLR-01", "DAY"));
		AtomicInteger calls = new AtomicInteger();
		ChangeEventPublisher failsOnSecond = message -> {
			if (calls.incrementAndGet() == 2) {
				throw new IllegalStateException("broker unavailable");
			}
			kafkaPublisher.publish(message);
		};

		var first = new OutboxRelay(pendingOutbox, failsOnSecond, tx, 100, new SimpleMeterRegistry()).relayOnce();

		assertThat(first.published()).isEqualTo(1);
		assertThat(first.stoppedOnFailure()).isTrue();
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT published_at IS NOT NULL AS published, attempts, last_error_code FROM outbox ORDER BY id");
		assertThat(rows.get(0)).containsEntry("published", true);
		assertThat(rows.get(1)).containsEntry("published", false).containsEntry("attempts", 1)
				.containsEntry("last_error_code", "IllegalStateException");
		assertThat(rows.get(2)).containsEntry("published", false).containsEntry("attempts", 0);

		var second = relay.relayOnce();

		assertThat(second.published()).isEqualTo(2);
		assertThat(unpublished()).isZero();
	}

	/**
	 * Acknowledges each event a little later on another thread (or fails the ones whose
	 * key is {@code failKey}) and records how many events are in flight at once.
	 */
	private static final class SlowBroker implements ChangeEventPublisher {
		final String failKey;
		final List<String> sentKeys = new CopyOnWriteArrayList<>();
		final Map<String, AtomicInteger> inFlightByKey = new ConcurrentHashMap<>();
		final AtomicInteger inFlight = new AtomicInteger();
		final AtomicInteger maxInFlight = new AtomicInteger();
		final AtomicInteger maxSameKeyInFlight = new AtomicInteger();

		SlowBroker(String failKey) {
			this.failKey = failKey;
		}

		@Override
		public void publish(OutboxMessage message) {
			throw new UnsupportedOperationException("the relay sends asynchronously");
		}

		@Override
		public CompletableFuture<Void> publishAsync(OutboxMessage message) {
			sentKeys.add(message.key());
			maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			AtomicInteger sameKey = inFlightByKey.computeIfAbsent(message.key(), k -> new AtomicInteger());
			maxSameKeyInFlight.accumulateAndGet(sameKey.incrementAndGet(), Math::max);
			return CompletableFuture.runAsync(() -> {
				inFlight.decrementAndGet();
				sameKey.decrementAndGet();
				if (message.key().equals(failKey)) {
					throw new IllegalStateException("broker unavailable");
				}
			}, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
		}
	}

	private void twoEventsEachForThreeEmployees() {
		for (String id : List.of("E1", "E2", "E3")) {
			applyService.apply(ACME, "workday", create(id, 1, "BLR-01", "DAY"));
		}
		for (String id : List.of("E1", "E2", "E3")) {
			applyService.apply(ACME, "workday", update(id, 2, "HYD-02", "DAY"));
		}
	}

	@Test
	void differentEmployeesAreInFlightTogetherButOneEmployeeNeverHasTwo() {
		twoEventsEachForThreeEmployees();
		SlowBroker broker = new SlowBroker(null);

		var result = new OutboxRelay(pendingOutbox, broker, tx, 100, new SimpleMeterRegistry()).relayOnce();

		assertThat(result.published()).isEqualTo(6);
		assertThat(unpublished()).isZero();
		assertThat(broker.maxInFlight).hasValue(3);
		assertThat(broker.maxSameKeyInFlight).hasValue(1);
		assertThat(broker.sentKeys).containsExactly("acme:E1", "acme:E2", "acme:E3", "acme:E1", "acme:E2", "acme:E3");
	}

	@Test
	void aFailureInsideASegmentMarksOnlyTheEventsBeforeItAndSendsNothingAfterTheSegment() {
		twoEventsEachForThreeEmployees();
		SlowBroker broker = new SlowBroker("acme:E2");

		var result = new OutboxRelay(pendingOutbox, broker, tx, 100, new SimpleMeterRegistry()).relayOnce();

		assertThat(result.published()).isEqualTo(1);
		assertThat(result.stoppedOnFailure()).isTrue();
		// E3's first event was in flight with E2's and may have reached the broker; it stays
		// unpublished and is sent again (consumers drop the duplicate). No second event of
		// any employee was sent, so none can overtake E2's failed first event.
		assertThat(broker.sentKeys).containsExactly("acme:E1", "acme:E2", "acme:E3");
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT aggregate_id, published_at IS NOT NULL AS published, attempts FROM outbox ORDER BY id");
		assertThat(rows).extracting(row -> row.get("published")).containsExactly(true, false, false, false, false, false);
		assertThat(rows.get(1)).containsEntry("attempts", 1);
	}

	@Test
	void onlyOneRelayIsActiveAtATime() throws Exception {
		applyService.apply(ACME, "workday", create("E000042", 1, "BLR-01", "DAY"));
		CountDownLatch otherRelayHoldsLock = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread otherRelay = new Thread(() -> tx.executeWithoutResult(status -> {
			pendingOutbox.tryBecomeActiveRelay();
			otherRelayHoldsLock.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		otherRelay.start();
		otherRelayHoldsLock.await();

		var whileOtherActive = relay.relayOnce();
		release.countDown();
		otherRelay.join();

		assertThat(whileOtherActive.otherRelayActive()).isTrue();
		assertThat(whileOtherActive.published()).isZero();
		assertThat(relay.relayOnce().published()).isEqualTo(1);
	}
}
