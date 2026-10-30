package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.application.Bookings;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
				""", new MapSqlParameterSource()
				.addValue("tenantId", tenantId.value())
				.addValue("employeeId", employeeId.value())
				.addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)), Integer.class);
		return cancelled == null ? 0 : cancelled;
	}
}
