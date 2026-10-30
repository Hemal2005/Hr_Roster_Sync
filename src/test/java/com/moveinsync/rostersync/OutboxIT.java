package com.moveinsync.rostersync;

import static com.moveinsync.rostersync.support.TestRecords.create;
import static com.moveinsync.rostersync.support.TestRecords.deactivate;
import static com.moveinsync.rostersync.support.TestRecords.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.moveinsync.rostersync.application.ApplyOutcome;
import com.moveinsync.rostersync.application.ApplyResult;
import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.EmployeeService;
import com.moveinsync.rostersync.application.TransportChanges;
import com.moveinsync.rostersync.domain.ChangeEvent;
import com.moveinsync.rostersync.domain.ChangeEventType;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.Outbox;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Every applied change writes exactly one change event, in the same transaction as the
 * change; nothing else writes one, and the event carries ids and field names only.
 */
class OutboxIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");

	@Autowired
	ApplyService applyService;

	@Autowired
	EmployeeService employeeService;

	@MockitoSpyBean
	Outbox outbox;

	@BeforeEach
	void tenant() {
		givenTenant("acme", "BLR-01", "HYD-02");
	}

	private List<Map<String, Object>> outboxRows() {
		return jdbc.queryForList(
				"SELECT event_type, aggregate_id, payload::text AS payload, published_at FROM outbox ORDER BY id");
	}

	private ApplyResult apply(IncomingHrRecord record) {
		return applyService.apply(ACME, "workday", record);
	}

	@Test
	void appliedChangeWritesOneUnpublishedEventWithIdsAndFieldNamesOnly() {
		apply(create("E000042", 1, "BLR-01", "DAY"));

		assertThat(outboxRows()).singleElement().satisfies(row -> {
			assertThat(row).containsEntry("event_type", "EMPLOYEE_CREATED").containsEntry("aggregate_id", "E000042");
			assertThat(row.get("published_at")).isNull();
			assertThat((String) row.get("payload"))
					.contains("\"employeeId\": \"E000042\"", "\"hrVersion\": 1", "\"officeCode\"", "\"phone\"")
					.doesNotContain("Priyanka", "Raghunathan", "@acme.example", "+919876543210", "BLR-01");
		});
	}

	@Test
	void updateEventListsOnlyTheChangedFields() {
		apply(create("E000042", 1, "BLR-01", "DAY"));
		apply(update("E000042", 2, "HYD-02", "DAY"));

		assertThat(outboxRows()).extracting(row -> row.get("event_type"))
				.containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_UPDATED");
		assertThat((String) outboxRows().get(1).get("payload")).contains("\"changedFields\": [\"officeCode\"]");
	}

	@Test
	void deactivationAndReactivationHaveTheirOwnTypes() {
		apply(create("E000042", 1, "BLR-01", "DAY"));
		apply(deactivate("E000042", 2));
		apply(update("E000042", 3, "BLR-01", "DAY"));

		assertThat(outboxRows()).extracting(row -> row.get("event_type"))
				.containsExactly("EMPLOYEE_CREATED", "EMPLOYEE_DEACTIVATED", "EMPLOYEE_REACTIVATED");
	}

	@Test
	void duplicateStaleAndRejectedRecordsWriteNoEvent() {
		apply(update("E000042", 5, "BLR-01", "DAY"));

		apply(update("E000042", 5, "BLR-01", "DAY"));
		apply(update("E000042", 4, "HYD-02", "DAY"));
		apply(update("E000042", 6, "CHN-99", "DAY"));

		assertThat(outboxRows()).hasSize(1);
	}

	@Test
	void transportEditWritesATransportEventWithoutTheAddress() {
		apply(create("E000042", 1, "BLR-01", "DAY"));

		employeeService.updateTransport(ACME, new EmployeeId("E000042"),
				new TransportChanges(true, "12 Lake View Road", false, null), 0);

		assertThat(outboxRows()).extracting(row -> row.get("event_type"))
				.containsExactly("EMPLOYEE_CREATED", "TRANSPORT_UPDATED");
		assertThat((String) outboxRows().get(1).get("payload"))
				.contains("\"changedFields\": [\"pickupPoint\"]", "\"transportVersion\": 1")
				.doesNotContain("Lake View");
	}

	@Test
	void eventWrittenInSameTransaction() {
		doThrow(new DataAccessResourceFailureException("simulated outbox failure"))
				.when(outbox).enqueue(any(ChangeEvent.class));

		ApplyResult result = apply(create("E000042", 1, "BLR-01", "DAY"));

		// The event could not be written, so the change it describes was not committed either.
		assertThat(result).isEqualTo(ApplyResult.rejected(ErrorCode.PROCESSING_FAILED));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_event_log", Integer.class)).isZero();
		assertThat(outboxRows()).isEmpty();
	}

	@Test
	void anEventCannotBeWrittenOutsideATransaction() {
		var event = new ChangeEvent(UUID.randomUUID(), ACME, new EmployeeId("E000042"),
				ChangeEventType.EMPLOYEE_UPDATED,
				EmployeeStatus.ACTIVE, 1, 0, Set.of(),
				Instant.now());

		assertThatThrownBy(() -> outbox.enqueue(event)).hasRootCauseInstanceOf(IllegalStateException.class);
		assertThat(outboxRows()).isEmpty();
	}

	@Test
	void afterAFailedRecordTheNextOneStillApplies() {
		doThrow(new DataAccessResourceFailureException("simulated outbox failure"))
				.doCallRealMethod()
				.when(outbox).enqueue(any(ChangeEvent.class));

		assertThat(apply(create("E000042", 1, "BLR-01", "DAY")).outcome()).isEqualTo(ApplyOutcome.REJECTED);
		assertThat(apply(create("E000043", 1, "BLR-01", "DAY")).outcome()).isEqualTo(ApplyOutcome.APPLIED);
		assertThat(apply(create("E000042", 1, "BLR-01", "DAY")).outcome()).isEqualTo(ApplyOutcome.APPLIED);
	}
}
