package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.ErrorCode;
import java.util.Objects;

/**
 * What happened to one record. {@code errorCode} is set only for REJECTED.
 */
public record ApplyResult(ApplyOutcome outcome, ErrorCode errorCode) {

	public ApplyResult {
		Objects.requireNonNull(outcome, "outcome");
		if ((outcome == ApplyOutcome.REJECTED) != (errorCode != null)) {
			throw new IllegalArgumentException("an error code is required for, and only for, REJECTED");
		}
	}

	public static ApplyResult of(ApplyOutcome outcome) {
		return new ApplyResult(outcome, null);
	}

	public static ApplyResult rejected(ErrorCode errorCode) {
		return new ApplyResult(ApplyOutcome.REJECTED, errorCode);
	}
}
