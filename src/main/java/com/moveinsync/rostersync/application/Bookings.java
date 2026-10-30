package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import java.time.Instant;

public interface Bookings {

	/**
	 * Cancels the employee's live bookings on trips that start after {@code now} and frees
	 * their seats. Bookings on trips that already ran stay as they are. Returns how many
	 * bookings were cancelled.
	 */
	int cancelFutureBookings(TenantId tenantId, EmployeeId employeeId, Instant now);
}
