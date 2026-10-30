package com.moveinsync.rostersync.domain.reconciliation;

import com.moveinsync.rostersync.domain.EmployeeStatus;
import java.time.Instant;
import java.util.Objects;

/**
 * The part of a stored employee the diff needs; no personal data.
 */
public record StoredEntry(String employeeId, long hrVersion, String hashHex, EmployeeStatus status,
		Instant lastHrEventAt) {

	public StoredEntry {
		Objects.requireNonNull(employeeId, "employeeId");
		Objects.requireNonNull(hashHex, "hashHex");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(lastHrEventAt, "lastHrEventAt");
	}
}
