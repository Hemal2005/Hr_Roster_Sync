package com.moveinsync.rostersync.api.booking;

import static com.moveinsync.rostersync.support.TestEvents.event;
import static com.moveinsync.rostersync.support.TestRecords.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.OutboxRelay;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.support.ApiTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class BookingApiIT extends ApiTest {

	@Autowired
	ApplyService applyService;

	@Autowired
	OutboxRelay relay;

	@BeforeEach
	void setUp() {
		givenTenant("acme", "BLR-01");
		givenTenant("globex", "BLR-01");
		applyService.apply(new TenantId("acme"), "workday", create("E000042", 1, "BLR-01", "DAY"));
		applyService.apply(new TenantId("acme"), "workday", create("E000043", 1, "BLR-01", "DAY"));
		trip("acme", "T1", Instant.now().plus(Duration.ofDays(1)), 2);
		trip("acme", "T-FULL", Instant.now().plus(Duration.ofDays(1)), 1);
		trip("acme", "T-GONE", Instant.now().minus(Duration.ofHours(1)), 4);
		trip("globex", "T-GLOBEX", Instant.now().plus(Duration.ofDays(1)), 4);
		jdbc.update("UPDATE trip SET seats_booked = 1 WHERE id = 'T-FULL'");
	}

	private void trip(String tenant, String id, Instant pickup, int capacity) {
		jdbc.update("INSERT INTO trip (tenant_id, id, office_code, pickup_time, capacity) VALUES (?, ?, 'BLR-01', ?, ?)",
				tenant, id, Timestamp.from(pickup), capacity);
	}

	private ResultActions book(String tripId, String employeeId) throws Exception {
		return book(tripId, employeeId, "ADMIN");
	}

	private ResultActions book(String tripId, String employeeId, String role) throws Exception {
		return mvc.perform(post("/api/v1/bookings")
				.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", role))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"tripId\":\"%s\",\"employeeId\":\"%s\"}".formatted(tripId, employeeId)));
	}

	private int seats(String tripId) {
		return jdbc.queryForObject("SELECT seats_booked FROM trip WHERE id = ?", Integer.class, tripId);
	}

	@Test
	void bookingTakesASeat() throws Exception {
		book("T1", "E000042")
				.andExpect(status().isCreated())
				.andExpect(header().exists(HttpHeaders.LOCATION))
				.andExpect(jsonPath("$.status").value("BOOKED"))
				.andExpect(jsonPath("$.tripId").value("T1"));

		assertThat(seats("T1")).isEqualTo(1);
	}

	@Test
	void fullDepartedAndDuplicateBookingsAreRefused() throws Exception {
		book("T-FULL", "E000042").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIP_FULL"));
		book("T-GONE", "E000042").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIP_DEPARTED"));
		book("T1", "E000042").andExpect(status().isCreated());
		book("T1", "E000042").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_BOOKED"));

		assertThat(seats("T1")).isEqualTo(1);
		assertThat(seats("T-FULL")).isEqualTo(1);
	}

	@Test
	void unknownTripEmployeeOrAnotherTenantsTripIs404() throws Exception {
		book("T-NOPE", "E000042").andExpect(status().isNotFound());
		book("T1", "E999999").andExpect(status().isNotFound());
		book("T-GLOBEX", "E000042").andExpect(status().isNotFound());
	}

	@Test
	void onlyAdminsMayBook() throws Exception {
		book("T1", "E000042", "EMPLOYEE").andExpect(status().isForbidden());
		book("T1", "E000042", "HR_INTEGRATION").andExpect(status().isForbidden());
	}

	@Test
	void listingNeedsAnEmployeeId() throws Exception {
		mvc.perform(get("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields", contains("employeeId")));
	}

	@Test
	void anExitSentByHrCancelsTheBookingEndToEnd() throws Exception {
		book("T1", "E000042").andExpect(status().isCreated());
		book("T1", "E000043").andExpect(status().isCreated());

		mvc.perform(post("/api/v1/hr/events:batch")
						.header(HttpHeaders.AUTHORIZATION, bearer("acme", "workday", "HR_INTEGRATION"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(batch(List.of(event("DEACTIVATE", "E000042", 2, "BLR-01", "DAY")))))
				.andExpect(jsonPath("$.results[0].outcome").value("APPLIED"));
		relay.relayOnce();

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				mvc.perform(get("/api/v1/bookings").param("employeeId", "E000042")
								.header(HttpHeaders.AUTHORIZATION, bearer("acme", "admin-1", "ADMIN")))
						.andExpect(jsonPath("$[0].status").value("CANCELLED"))
						.andExpect(jsonPath("$[0].cancelReason").value("EMPLOYEE_DEACTIVATED")));
		assertThat(seats("T1")).isEqualTo(1);
		book("T1", "E000042").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPLOYEE_INACTIVE"));
	}
}
