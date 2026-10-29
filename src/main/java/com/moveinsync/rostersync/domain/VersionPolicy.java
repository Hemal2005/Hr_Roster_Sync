package com.moveinsync.rostersync.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Decides what to do with an incoming HR event for one employee, given what is stored.
 *
 * <pre>
 * nothing stored                      -> APPLY
 * incoming &gt; stored                    -> APPLY
 * incoming == stored, same hash       -> DUPLICATE
 * incoming == stored, different hash  -> CONFLICT
 * incoming &lt; stored                    -> STALE
 * </pre>
 *
 * Because events carry the full HR state, accepting only newer versions gives the same
 * result as applying every event in version order. The caller must read {@code stored}
 * under a row lock so that two concurrent events cannot both see the same version.
 */
public final class VersionPolicy {

	public VersionDecision decide(Optional<StoredVersion> stored, long incomingVersion, PayloadHash incomingHash) {
		Objects.requireNonNull(incomingHash, "incomingHash");
		if (incomingVersion < 1) {
			throw new IllegalArgumentException("version must be >= 1");
		}
		if (stored.isEmpty()) {
			return VersionDecision.APPLY;
		}
		long storedVersion = stored.get().version();
		if (incomingVersion > storedVersion) {
			return VersionDecision.APPLY;
		}
		if (incomingVersion < storedVersion) {
			return VersionDecision.STALE;
		}
		return incomingHash.equals(stored.get().hash()) ? VersionDecision.DUPLICATE : VersionDecision.CONFLICT;
	}
}
