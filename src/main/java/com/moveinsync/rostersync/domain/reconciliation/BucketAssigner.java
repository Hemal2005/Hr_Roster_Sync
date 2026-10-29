package com.moveinsync.rostersync.domain.reconciliation;

import com.moveinsync.rostersync.domain.EmployeeId;
import java.nio.charset.StandardCharsets;

/**
 * Maps an employee id to one of N buckets: floorMod(murmur3(id), N). The nightly diff
 * compares one bucket at a time, so the same function must be used for file rows and
 * for the bucket column stored with each employee. Changing N or the hash means
 * recomputing that column.
 */
public final class BucketAssigner {

	public static final int DEFAULT_BUCKET_COUNT = 64;

	private final int bucketCount;

	public BucketAssigner(int bucketCount) {
		// stored as SMALLINT
		if (bucketCount < 1 || bucketCount > Short.MAX_VALUE) {
			throw new IllegalArgumentException("bucketCount must be in [1, " + Short.MAX_VALUE + "]");
		}
		this.bucketCount = bucketCount;
	}

	public int bucketOf(EmployeeId employeeId) {
		int hash = Murmur3.hash32(employeeId.value().getBytes(StandardCharsets.UTF_8), 0);
		// floorMod, not %, because the hash can be negative
		return Math.floorMod(hash, bucketCount);
	}

	public int bucketCount() {
		return bucketCount;
	}
}
