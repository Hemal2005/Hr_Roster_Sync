package com.moveinsync.rostersync.domain;

import java.util.Objects;

/**
 * The HR version and payload hash currently stored for one employee.
 */
public record StoredVersion(long version, PayloadHash hash) {

	public StoredVersion {
		Objects.requireNonNull(hash, "hash");
		if (version < 1) {
			throw new IllegalArgumentException("version must be >= 1");
		}
	}

	public static StoredVersion of(Employee employee) {
		return new StoredVersion(employee.hrVersion(), employee.hrPayloadHash());
	}
}
