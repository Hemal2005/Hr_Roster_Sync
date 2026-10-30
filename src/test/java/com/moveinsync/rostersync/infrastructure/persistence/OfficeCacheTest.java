package com.moveinsync.rostersync.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OfficeCacheTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final TenantId GLOBEX = new TenantId("globex");

	/** Stands in for the database: a set of active codes, counting every lookup. */
	private static final class FakeOffices implements OfficeDirectory {
		final Set<String> active = new HashSet<>();
		int lookups;

		@Override
		public boolean isKnownOffice(TenantId tenantId, String officeCode) {
			lookups++;
			return active.contains(tenantId.value() + "/" + officeCode);
		}
	}

	private final AtomicLong nanos = new AtomicLong();
	private final Ticker ticker = nanos::get;
	private final FakeOffices database = new FakeOffices();
	private final CachingOfficeDirectory offices = new CachingOfficeDirectory(database, ticker);

	private void advance(Duration duration) {
		nanos.addAndGet(duration.toNanos());
	}

	@Test
	void aKnownOfficeIsLookedUpOnceAndThenServedFromTheCache() {
		database.active.add("acme/BLR-01");

		for (int i = 0; i < 100; i++) {
			assertThat(offices.isKnownOffice(ACME, "BLR-01")).isTrue();
		}

		assertThat(database.lookups).isEqualTo(1);
		assertThat(offices.cache().stats().hitCount()).isEqualTo(99);
	}

	@Test
	void aDeactivatedOfficeIsStillAcceptedUntilTheEntryExpiresAfterFiveMinutes() {
		database.active.add("acme/BLR-01");
		offices.isKnownOffice(ACME, "BLR-01");
		database.active.remove("acme/BLR-01");

		advance(Duration.ofMinutes(5).minusSeconds(1));
		assertThat(offices.isKnownOffice(ACME, "BLR-01")).isTrue();

		advance(Duration.ofSeconds(1));
		assertThat(offices.isKnownOffice(ACME, "BLR-01")).isFalse();
	}

	@Test
	void unknownAnswersAreNotCachedSoANewOfficeIsAcceptedAtOnce() {
		assertThat(offices.isKnownOffice(ACME, "HYD-02")).isFalse();
		database.active.add("acme/HYD-02");

		assertThat(offices.isKnownOffice(ACME, "HYD-02")).isTrue();
		assertThat(database.lookups).isEqualTo(2);
	}

	@Test
	void tenantsNeverShareAnEntry() {
		database.active.add("acme/BLR-01");
		offices.isKnownOffice(ACME, "BLR-01");

		assertThat(offices.isKnownOffice(GLOBEX, "BLR-01")).isFalse();
	}

	@Test
	void invalidateAllForcesTheNextLookupToTheDatabase() {
		database.active.add("acme/BLR-01");
		offices.isKnownOffice(ACME, "BLR-01");

		offices.invalidateAll();
		offices.isKnownOffice(ACME, "BLR-01");

		assertThat(database.lookups).isEqualTo(2);
	}
}
