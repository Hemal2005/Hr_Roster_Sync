package com.moveinsync.rostersync.application.reconciliation;

public enum RunStatus {
	/** Reading the export and building the plan. Nothing has changed yet. */
	RUNNING,
	/** Plan stored, brake passed, about to apply. */
	PLANNED,
	/** The safety brake tripped; nothing changes until an operator decides. */
	AWAITING_APPROVAL,
	APPLYING,
	COMPLETED,
	/** An operator rejected the plan; nothing was applied. */
	REJECTED,
	FAILED
}
