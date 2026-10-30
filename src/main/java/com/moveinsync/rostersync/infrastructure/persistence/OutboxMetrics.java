package com.moveinsync.rostersync.infrastructure.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How far behind the relay is, read from the outbox when Prometheus scrapes: how many
 * events are unsent, and how long the oldest has waited. Both use the partial index on
 * unsent rows. A failed query reports NaN rather than failing the scrape.
 */
@Component
class OutboxMetrics {

	private final JdbcTemplate jdbc;

	OutboxMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
		this.jdbc = jdbc;
		Gauge.builder("outbox.depth", this, OutboxMetrics::depth)
				.description("Change events written but not yet acknowledged by the broker")
				.register(meters);
		Gauge.builder("outbox.oldest.age", this, OutboxMetrics::oldestAgeSeconds)
				.description("How long the oldest unsent change event has waited; 0 when none")
				.baseUnit("seconds")
				.register(meters);
	}

	private double depth() {
		return query("SELECT count(*) FROM outbox WHERE published_at IS NULL");
	}

	private double oldestAgeSeconds() {
		return query("SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM outbox WHERE published_at IS NULL");
	}

	private double query(String sql) {
		try {
			Number value = jdbc.queryForObject(sql, Number.class);
			return value == null ? 0 : value.doubleValue();
		} catch (DataAccessException e) {
			return Double.NaN;
		}
	}
}
