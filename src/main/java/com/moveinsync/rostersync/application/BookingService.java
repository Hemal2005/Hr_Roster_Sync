package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Minimal seat booking, there to demonstrate what an employee's exit does to bookings.
 */
@Service
public class BookingService {

	private final Bookings bookings;
	private final EmployeeRepository employees;
	private final TransactionTemplate tx;
	private final Clock clock;

	BookingService(Bookings bookings, EmployeeRepository employees, TransactionTemplate tx, Clock clock) {
		this.bookings = bookings;
		this.employees = employees;
		this.tx = tx;
		this.clock = clock;
	}

	public Bookings.Booking book(TenantId tenantId, String tripId, EmployeeId employeeId) {
		return tx.execute(status -> {
			Bookings.Trip trip = bookings.lockTrip(tenantId, tripId)
					.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
			Employee employee = employees.find(tenantId, employeeId)
					.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
			if (employee.status() != EmployeeStatus.ACTIVE) {
				throw new DomainException(ErrorCode.EMPLOYEE_INACTIVE);
			}
			if (!trip.pickupTime().isAfter(clock.instant())) {
				throw new DomainException(ErrorCode.TRIP_DEPARTED);
			}
			if (trip.seatsBooked() >= trip.capacity()) {
				throw new DomainException(ErrorCode.TRIP_FULL);
			}
			if (bookings.hasLiveBooking(tenantId, tripId, employeeId)) {
				throw new DomainException(ErrorCode.ALREADY_BOOKED);
			}
			String bookingId = UUID.randomUUID().toString();
			bookings.addBooking(tenantId, bookingId, tripId, employeeId);
			return new Bookings.Booking(bookingId, tripId, employeeId.value(), "BOOKED", null, trip.pickupTime());
		});
	}

	public List<Bookings.Booking> forEmployee(TenantId tenantId, EmployeeId employeeId) {
		return bookings.forEmployee(tenantId, employeeId);
	}
}
