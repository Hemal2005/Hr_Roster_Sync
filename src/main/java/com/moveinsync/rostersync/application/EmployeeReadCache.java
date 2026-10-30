package com.moveinsync.rostersync.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Employee records for read endpoints only, for up to 30 seconds. This instance evicts an
 * entry after every change it commits; another instance's change shows here within the
 * TTL. Writes and version checks never use it: they always lock and read the row.
 * Missing employees are not cached, so a new employee is readable at once.
 */
@Component
public class EmployeeReadCache implements LocalCache {

	static final Duration TTL = Duration.ofSeconds(30);
	static final long MAX_ENTRIES = 50_000;

	private record Key(String tenantId, String employeeId) {
	}

	private final Cache<Key, Employee> cache;

	@Autowired
	EmployeeReadCache(MeterRegistry meters) {
		this(Ticker.systemTicker());
		CaffeineCacheMetrics.monitor(meters, cache, "employees");
	}

	EmployeeReadCache(Ticker ticker) {
		this.cache = Caffeine.newBuilder()
				.expireAfterWrite(TTL)
				.maximumSize(MAX_ENTRIES)
				.ticker(ticker)
				.recordStats()
				.build();
	}

	/** The cached record, or {@code load}'s answer, which is cached only when present. */
	public Optional<Employee> get(TenantId tenantId, EmployeeId employeeId, Supplier<Optional<Employee>> load) {
		return Optional.ofNullable(cache.get(new Key(tenantId.value(), employeeId.value()), key -> load.get().orElse(null)));
	}

	/** Called after a change to this employee has committed. */
	public void evict(TenantId tenantId, EmployeeId employeeId) {
		cache.invalidate(new Key(tenantId.value(), employeeId.value()));
	}

	@Override
	public void invalidateAll() {
		cache.invalidateAll();
	}
}
