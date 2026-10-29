package com.moveinsync.rostersync.domain;

/**
 * A business rule refused the operation. The message is the code alone so that no
 * record value can reach a log line or an error response through it.
 */
public class DomainException extends RuntimeException {

	private final ErrorCode code;

	public DomainException(ErrorCode code) {
		super(code.name());
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}
}
