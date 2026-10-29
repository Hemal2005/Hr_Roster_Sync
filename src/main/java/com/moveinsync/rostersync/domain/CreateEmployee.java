package com.moveinsync.rostersync.domain;

import java.util.Objects;

public record CreateEmployee(EventHeader header, HrOwnedFields fields) implements HrEvent {

	public CreateEmployee {
		Objects.requireNonNull(header, "header");
		Objects.requireNonNull(fields, "fields");
	}
}
