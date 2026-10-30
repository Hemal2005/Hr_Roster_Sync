package com.moveinsync.rostersync.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.TestEvents;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class EmployeeReadCacheTest {

	private static final TenantId ACME = TestEvents.TENANT;
	private static final EmployeeId E42 = TestEvents.EMPLOYEE;

	private final AtomicLong nanos = new AtomicLong();
	private final EmployeeReadCache cache = new EmployeeReadCache((Ticker) nanos::get);
	private final AtomicInteger loads = new AtomicInteger();

	private Supplier<Optional<Employee>> database(Optional<Employee> answer) {
		return () -> {
			loads.incrementAndGet();
			return answer;
		};
	}

	private static Employee employee() {
		return Employee.createFrom(TestEvents.create(1, "BLR-01", "DAY"), TestEvents.hash(1));
	}

	@Test
	void secondReadWithinTheTtlDoesNotLoad() {
		Optional<Employee> stored = Optional.of(employee());

		cache.get(ACME, E42, database(stored));
		cache.get(ACME, E42, database(stored));

		assertThat(loads).hasValue(1);
	}

	@Test
	void entryExpiresAfterThirtySeconds() {
		Optional<Employee> stored = Optional.of(employee());
		cache.get(ACME, E42, database(stored));

		nanos.addAndGet(Duration.ofSeconds(30).toNanos());
		cache.get(ACME, E42, database(stored));

		assertThat(loads).hasValue(2);
	}

	@Test
	void evictAfterALocalWriteMakesTheNextReadLoad() {
		Optional<Employee> stored = Optional.of(employee());
		cache.get(ACME, E42, database(stored));

		cache.evict(ACME, E42);
		cache.get(ACME, E42, database(stored));

		assertThat(loads).hasValue(2);
	}

	@Test
	void missingEmployeeIsNotCached() {
		assertThat(cache.get(ACME, E42, database(Optional.empty()))).isEmpty();

		assertThat(cache.get(ACME, E42, database(Optional.of(employee())))).isPresent();
		assertThat(loads).hasValue(2);
	}

	@Test
	void sameIdInAnotherTenantIsAnotherEntry() {
		cache.get(ACME, E42, database(Optional.of(employee())));

		assertThat(cache.get(new TenantId("globex"), E42, database(Optional.empty()))).isEmpty();
		assertThat(loads).hasValue(2);
	}
}
