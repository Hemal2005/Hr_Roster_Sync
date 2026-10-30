package com.moveinsync.rostersync.domain;

import java.util.Objects;

/**
 * An exit. It may carry the employee's full HR state (as the nightly file's INACTIVE
 * rows do); {@code fields} is null when it carries only the header. With full state an
 * exit that arrives before any other event for the employee can still be recorded, so
 * older events arriving later are correctly stale.
 */
public record DeactivateEmployee(EventHeader header, HrOwnedFields fields) implements HrEvent {

	public DeactivateEmployee {
		Objects.requireNonNull(header, "header");
	}

	public DeactivateEmployee(EventHeader header) {
		this(header, null);
	}

	public boolean carriesFullState() {
		return fields != null;
	}
}
