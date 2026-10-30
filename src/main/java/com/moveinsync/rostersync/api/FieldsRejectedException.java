package com.moveinsync.rostersync.api;

import com.moveinsync.rostersync.domain.ErrorCode;
import java.util.List;

/**
 * A request named fields it may not send. Carries the field names (never values) so the
 * problem body can list them.
 */
public class FieldsRejectedException extends RuntimeException {

	private final ErrorCode code;
	private final List<String> fields;

	public FieldsRejectedException(ErrorCode code, List<String> fields) {
		super(code.name());
		this.code = code;
		this.fields = List.copyOf(fields);
	}

	public ErrorCode code() {
		return code;
	}

	public List<String> fields() {
		return fields;
	}
}
