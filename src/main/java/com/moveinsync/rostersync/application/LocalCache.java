package com.moveinsync.rostersync.application;

/**
 * An in-process cache. Each instance of the service holds its own copy, so after a change
 * made elsewhere an entry can be stale for at most the cache's time-to-live.
 */
public interface LocalCache {

	/** Drops every entry, e.g. after data was fixed directly in the database. */
	void invalidateAll();
}
