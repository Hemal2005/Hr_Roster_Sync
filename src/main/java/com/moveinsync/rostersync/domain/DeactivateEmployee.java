package com.moveinsync.rostersync.domain;

import java.util.Objects;

public record DeactivateEmployee(EventHeader header) implements HrEvent {

	public DeactivateEmployee {
		Objects.requireNonNull(header, "header");
	}
}
