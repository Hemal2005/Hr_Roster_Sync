package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.ErrorCode;
import java.util.Objects;

/**
 * A rejected field: the code and the field's name, never its value.
 */
public record ValidationError(ErrorCode code, String field) {

	public ValidationError {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(field, "field");
	}
}
