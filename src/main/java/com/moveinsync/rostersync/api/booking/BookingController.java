package com.moveinsync.rostersync.api.booking;

import com.moveinsync.rostersync.application.BookingService;
import com.moveinsync.rostersync.application.Bookings;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal booking endpoints, enough to show an exit cancelling future bookings.
 */
@RestController
@RequestMapping("/api/v1/bookings")
class BookingController {

	private final BookingService bookings;

	BookingController(BookingService bookings) {
		this.bookings = bookings;
	}

	record BookingRequest(@NotBlank String tripId, @NotBlank String employeeId) {
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Bookings.Booking> book(TenantId tenantId, @Valid @RequestBody BookingRequest request) {
		Bookings.Booking booking = bookings.book(tenantId, request.tripId(), new EmployeeId(request.employeeId()));
		return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.id())).body(booking);
	}

	@GetMapping
	List<Bookings.Booking> forEmployee(TenantId tenantId, @RequestParam String employeeId) {
		return bookings.forEmployee(tenantId, new EmployeeId(employeeId));
	}
}
