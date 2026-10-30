package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.ChangeEvent;
import com.moveinsync.rostersync.domain.ChangeEventType;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * When an employee leaves, cancels their future cab bookings and frees the seats. The
 * event id is recorded in the same transaction, so a redelivered event changes nothing.
 */
@Service
public class DeactivationHandler {

	static final String CONSUMER = "booking-cancellation";

	private static final Logger log = LoggerFactory.getLogger(DeactivationHandler.class);

	private final ProcessedMessages processed;
	private final Bookings bookings;
	private final TransactionTemplate tx;
	private final Clock clock;

	DeactivationHandler(ProcessedMessages processed, Bookings bookings, TransactionTemplate tx, Clock clock) {
		this.processed = processed;
		this.bookings = bookings;
		this.tx = tx;
		this.clock = clock;
	}

	/** Returns how many bookings were cancelled; 0 for other event types and redeliveries. */
	public int handle(ChangeEvent event) {
		if (event.type() != ChangeEventType.EMPLOYEE_DEACTIVATED) {
			return 0;
		}
		MDC.put("tenantId", event.tenantId().value());
		MDC.put("employeeId", event.employeeId().value());
		MDC.put("eventId", event.eventId().toString());
		try {
			return tx.execute(status -> {
				if (!processed.markProcessed(CONSUMER, event.eventId())) {
					log.info("deactivation already handled; redelivery ignored");
					return 0;
				}
				int cancelled = bookings.cancelFutureBookings(event.tenantId(), event.employeeId(), clock.instant());
				log.info("deactivation handled; cancelled {} future bookings", cancelled);
				return cancelled;
			});
		} finally {
			MDC.remove("tenantId");
			MDC.remove("employeeId");
			MDC.remove("eventId");
		}
	}
}
