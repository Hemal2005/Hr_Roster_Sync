package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.application.Bookings;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBookings implements Bookings {

	private final NamedParameterJdbcTemplate jdbc;

	JdbcBookings(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int cancelFutureBookings(TenantId tenantId, EmployeeId employeeId, Instant now) {
		// One statement: cancel the bookings and give each affected trip back its seats, so
		// a booking can never be cancelled without its seat being freed or the reverse.
		Integer cancelled = jdbc.queryForObject("""
				WITH cancelled AS (
				    UPDATE booking b
				    SET status = 'CANCELLED', cancel_reason = 'EMPLOYEE_DEACTIVATED', updated_at = now()
				    FROM trip t
				    WHERE b.tenant_id = :tenantId AND b.employee_id = :employeeId AND b.status = 'BOOKED'
				      AND t.tenant_id = b.tenant_id AND t.id = b.trip_id AND t.pickup_time > :now
				    RETURNING b.trip_id
				), freed AS (
				    UPDATE trip
				    SET seats_booked = trip.seats_booked - per_trip.seats
				    FROM (SELECT trip_id, count(*) AS seats FROM cancelled GROUP BY trip_id) per_trip
				    WHERE trip.tenant_id = :tenantId AND trip.id = per_trip.trip_id
				    RETURNING trip.id
				)
				SELECT count(*) FROM cancelled
				""", params(tenantId).addValue("employeeId", employeeId.value()).addValue("now", utc(now)),
				Integer.class);
		return cancelled == null ? 0 : cancelled;
	}

	@Override
	public Optional<Trip> lockTrip(TenantId tenantId, String tripId) {
		return jdbc.query("""
				SELECT id, pickup_time, capacity, seats_booked FROM trip
				WHERE tenant_id = :tenantId AND id = :tripId
				FOR UPDATE
				""", params(tenantId).addValue("tripId", tripId), (rs, row) -> new Trip(
				rs.getString("id"),
				rs.getObject("pickup_time", OffsetDateTime.class).toInstant(),
				rs.getInt("capacity"),
				rs.getInt("seats_booked"))).stream().findFirst();
	}

	@Override
	public boolean hasLiveBooking(TenantId tenantId, String tripId, EmployeeId employeeId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM booking WHERE tenant_id = :tenantId AND trip_id = :tripId
				               AND employee_id = :employeeId AND status = 'BOOKED')
				""", params(tenantId).addValue("tripId", tripId).addValue("employeeId", employeeId.value()),
				Boolean.class));
	}

	@Override
	public void addBooking(TenantId tenantId, String bookingId, String tripId, EmployeeId employeeId) {
		var params = params(tenantId).addValue("bookingId", bookingId).addValue("tripId", tripId)
				.addValue("employeeId", employeeId.value());
		jdbc.update("""
				INSERT INTO booking (tenant_id, id, trip_id, employee_id, status)
				VALUES (:tenantId, :bookingId, :tripId, :employeeId, 'BOOKED')
				""", params);
		jdbc.update("UPDATE trip SET seats_booked = seats_booked + 1 WHERE tenant_id = :tenantId AND id = :tripId",
				params);
	}

	@Override
	public List<Booking> forEmployee(TenantId tenantId, EmployeeId employeeId) {
		return jdbc.query("""
				SELECT b.id, b.trip_id, b.employee_id, b.status, b.cancel_reason, t.pickup_time
				FROM booking b JOIN trip t ON t.tenant_id = b.tenant_id AND t.id = b.trip_id
				WHERE b.tenant_id = :tenantId AND b.employee_id = :employeeId
				ORDER BY t.pickup_time
				""", params(tenantId).addValue("employeeId", employeeId.value()), (rs, row) -> new Booking(
				rs.getString("id"),
				rs.getString("trip_id"),
				rs.getString("employee_id"),
				rs.getString("status"),
				rs.getString("cancel_reason"),
				rs.getObject("pickup_time", OffsetDateTime.class).toInstant()));
	}

	private static MapSqlParameterSource params(TenantId tenantId) {
		return new MapSqlParameterSource().addValue("tenantId", tenantId.value());
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
