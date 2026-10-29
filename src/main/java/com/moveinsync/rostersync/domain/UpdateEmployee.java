package com.moveinsync.rostersync.domain;

import java.util.Objects;

public record UpdateEmployee(EventHeader header, HrOwnedFields fields) implements HrEvent {

	public UpdateEmployee {
		Objects.requireNonNull(header, "header");
		Objects.requireNonNull(fields, "fields");
	}
}
