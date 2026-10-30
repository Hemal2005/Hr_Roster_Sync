package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface Bookings {

	record Trip(String id, Instant pickupTime, int capacity, int seatsBooked) {
	}

	record Booking(String id, String tripId, String employeeId, String status, String cancelReason,
			Instant pickupTime) {
	}

	/**
	 * Cancels the employee's live bookings on trips that start after {@code now} and frees
	 * their seats. Bookings on trips that already ran stay as they are. Returns how many
	 * bookings were cancelled.
	 */
	int cancelFutureBookings(TenantId tenantId, EmployeeId employeeId, Instant now);

	/** The trip, locked until the transaction ends so seat counts cannot race. */
	Optional<Trip> lockTrip(TenantId tenantId, String tripId);

	boolean hasLiveBooking(TenantId tenantId, String tripId, EmployeeId employeeId);

	/** Inserts a live booking and takes one seat on its trip. */
	void addBooking(TenantId tenantId, String bookingId, String tripId, EmployeeId employeeId);

	List<Booking> forEmployee(TenantId tenantId, EmployeeId employeeId);
}
