package com.moveinsync.rostersync.domain;

import java.util.Set;

/**
 * Result of sorting the field names a writer submitted.
 *
 * @param accepted fields the writer owns and may change
 * @param foreign  fields owned by someone else: ignored and counted, never applied
 * @param unknown  names that are not employee attributes at all
 */
public record OwnershipCheck(Set<EmployeeField> accepted, Set<EmployeeField> foreign, Set<String> unknown) {

	public OwnershipCheck {
		accepted = Set.copyOf(accepted);
		foreign = Set.copyOf(foreign);
		unknown = Set.copyOf(unknown);
	}

	public boolean hasViolations() {
		return !foreign.isEmpty();
	}
}
