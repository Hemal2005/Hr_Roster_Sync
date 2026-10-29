package com.moveinsync.rostersync.domain;

/**
 * Stable codes returned to clients and written to logs. Codes never carry field values.
 */
public enum ErrorCode {
	MISSING_REQUIRED_FIELD,
	INVALID_VERSION,
	INVALID_EMAIL,
	INVALID_PHONE,
	UNKNOWN_OFFICE,
	UNKNOWN_EMPLOYEE,
	TRANSPORT_VERSION_MISMATCH
}
