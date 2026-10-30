package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.deactivate;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.DeactivationHandler;
import com.moveinsync.rostersync.application.OutboxRelay;
import com.moveinsync.rostersync.domain.ChangeEvent;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.infrastructure.messaging.ChangeEventJson;
import com.moveinsync.rostersync.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An employee's exit travels end to end: applied change → outbox → relay → broker →
 * consumer, which cancels the future bookings and frees their seats, exactly once.
 */
class DeactivationConsumerIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final Instant NOW = Instant.now();

	@Autowired
	ApplyService applyService;

	@Autowired
	OutboxRelay relay;

	@Autowired
	DeactivationHandler handler;

	@Autowired
	ChangeEventJson changeEventJson;

	@BeforeEach
	void employeesTripsAndBookings() {
		givenTenant("acme", "BLR-01");
		applyService.apply(ACME, "workday", create("E000042", 1, "BLR-01", "DAY"));
		applyService.apply(ACME, "workday", create("E000043", 1, "BLR-01", "DAY"));
		trip("T-PAST", NOW.minus(Duration.ofDays(1)), 1);
		trip("T-TOMORROW", NOW.plus(Duration.ofDays(1)), 2);
		trip("T-NEXT-WEEK", NOW.plus(Duration.ofDays(7)), 1);
		booking("B1", "T-PAST", "E000042");
		booking("B2", "T-TOMORROW", "E000042");
		booking("B3", "T-NEXT-WEEK", "E000042");
		booking("B4", "T-TOMORROW", "E000043");
		relay.relayOnce();
	}

	private void trip(String id, Instant pickup, int seatsBooked) {
		jdbc.update("INSERT INTO trip (tenant_id, id, office_code, pickup_time, capacity, seats_booked) "
				+ "VALUES ('acme', ?, 'BLR-01', ?, 4, ?)", id, Timestamp.from(pickup), seatsBooked);
	}

	private void booking(String id, String tripId, String employeeId) {
		jdbc.update("INSERT INTO booking (tenant_id, id, trip_id, employee_id, status) VALUES ('acme', ?, ?, ?, 'BOOKED')",
				id, tripId, employeeId);
	}

	private String status(String bookingId) {
		return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
	}

	private int seats(String tripId) {
		return jdbc.queryForObject("SELECT seats_booked FROM trip WHERE id = ?", Integer.class, tripId);
	}

	private ChangeEvent lastEvent() {
		return changeEventJson.read(
				jdbc.queryForObject("SELECT payload::text FROM outbox ORDER BY id DESC LIMIT 1", String.class));
	}

	private void awaitHandled(ChangeEvent event) {
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM processed_message WHERE event_id = ?", Integer.class, event.eventId()))
				.isEqualTo(1));
	}

	@Test
	void deactivationCancelsFutureBookingsAndFreesSeats() {
		applyService.apply(ACME, "workday", deactivate("E000042", 2));
		ChangeEvent exit = lastEvent();

		relay.relayOnce();
		awaitHandled(exit);

		assertThat(status("B1")).as("past trip stays as history").isEqualTo("BOOKED");
		assertThat(status("B2")).isEqualTo("CANCELLED");
		assertThat(status("B3")).isEqualTo("CANCELLED");
		assertThat(status("B4")).as("another employee's booking").isEqualTo("BOOKED");
		assertThat(seats("T-PAST")).isEqualTo(1);
		assertThat(seats("T-TOMORROW")).isEqualTo(1);
		assertThat(seats("T-NEXT-WEEK")).isZero();
		assertThat(jdbc.queryForObject("SELECT cancel_reason FROM booking WHERE id = 'B2'", String.class))
				.isEqualTo("EMPLOYEE_DEACTIVATED");
	}

	@Test
	void redeliveredDeactivationChangesNothing() {
		applyService.apply(ACME, "workday", deactivate("E000042", 2));
		ChangeEvent exit = lastEvent();
		relay.relayOnce();
		awaitHandled(exit);
		Map<String, Object> seatsAfterFirst = jdbc.queryForMap(
				"SELECT sum(seats_booked) AS seats, (SELECT count(*) FROM booking WHERE status = 'CANCELLED') AS cancelled FROM trip");

		int secondTime = handler.handle(exit);

		assertThat(secondTime).isZero();
		assertThat(jdbc.queryForMap(
				"SELECT sum(seats_booked) AS seats, (SELECT count(*) FROM booking WHERE status = 'CANCELLED') AS cancelled FROM trip"))
				.isEqualTo(seatsAfterFirst);
	}

	@Test
	void otherChangesCancelNothing() {
		applyService.apply(ACME, "workday", update("E000042", 2, "BLR-01", "NIGHT"));

		assertThat(handler.handle(lastEvent())).isZero();
		assertThat(status("B2")).isEqualTo("BOOKED");
		assertThat(seats("T-TOMORROW")).isEqualTo(2);
	}

	@Test
	void exitWithoutBookingsIsHandledCleanly() {
		jdbc.update("DELETE FROM booking WHERE employee_id = 'E000043'");
		applyService.apply(ACME, "workday", deactivate("E000043", 2));
		ChangeEvent exit = lastEvent();

		relay.relayOnce();
		awaitHandled(exit);

		assertThat(seats("T-TOMORROW")).isEqualTo(2);
	}
}
