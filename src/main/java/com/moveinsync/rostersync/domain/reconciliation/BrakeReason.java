package com.moveinsync.rostersync.domain.reconciliation;

public enum BrakeReason {
	/** The trailer is missing or its count disagrees with the rows read. */
	FILE_TRUNCATED,
	/** Employees to deactivate / active before the run reached the threshold. */
	MISSING_RATIO_EXCEEDED
}
