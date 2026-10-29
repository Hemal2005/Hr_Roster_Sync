package com.moveinsync.rostersync.domain;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Digest of the HR-owned state an event asserts. Two events with the same version and
 * the same hash are the same change; same version with a different hash is a conflict.
 */
public final class PayloadHash {

	private final byte[] bytes;

	public PayloadHash(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("hash must not be empty");
		}
		this.bytes = bytes.clone();
	}

	public byte[] bytes() {
		return bytes.clone();
	}

	public String hex() {
		return HexFormat.of().formatHex(bytes);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof PayloadHash hash && Arrays.equals(hash.bytes, bytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return "PayloadHash[" + hex() + "]";
	}
}
