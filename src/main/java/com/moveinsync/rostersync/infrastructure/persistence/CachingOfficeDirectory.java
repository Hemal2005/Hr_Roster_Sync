package com.moveinsync.rostersync.infrastructure.persistence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.rostersync.application.LocalCache;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import java.time.Duration;

/**
 * Remembers which office codes are active, for up to five minutes, so validating a batch
 * does not query the office table once per record. Only "known" answers are kept: a new
 * office is accepted at once, while a deactivated one may still be accepted until its
 * entry expires.
 */
class CachingOfficeDirectory implements OfficeDirectory, LocalCache {

	static final Duration TTL = Duration.ofMinutes(5);
	static final long MAX_ENTRIES = 10_000;

	private record Key(String tenantId, String officeCode) {
	}

	private final OfficeDirectory delegate;
	private final Cache<Key, Boolean> known;

	CachingOfficeDirectory(OfficeDirectory delegate, Ticker ticker) {
		this.delegate = delegate;
		this.known = Caffeine.newBuilder()
				.expireAfterWrite(TTL)
				.maximumSize(MAX_ENTRIES)
				.ticker(ticker)
				.recordStats()
				.build();
	}

	@Override
	public boolean isKnownOffice(TenantId tenantId, String officeCode) {
		Key key = new Key(tenantId.value(), officeCode);
		if (known.getIfPresent(key) != null) {
			return true;
		}
		boolean isKnown = delegate.isKnownOffice(tenantId, officeCode);
		if (isKnown) {
			known.put(key, Boolean.TRUE);
		}
		return isKnown;
	}

	@Override
	public void invalidateAll() {
		known.invalidateAll();
	}

	Cache<?, ?> cache() {
		return known;
	}
}
