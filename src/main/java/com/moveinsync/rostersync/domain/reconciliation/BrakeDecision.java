package com.moveinsync.rostersync.domain.reconciliation;

/**
 * Outcome of the safety brake. Both outcomes carry the numbers the operator sees.
 */
public sealed interface BrakeDecision permits BrakeDecision.Pass, BrakeDecision.Trip {

	long activeBefore();

	long toDeactivate();

	/** toDeactivate / activeBefore, for display; 0 when there were no active employees. */
	double missingRatio();

	record Pass(long activeBefore, long toDeactivate, double missingRatio) implements BrakeDecision {
	}

	record Trip(BrakeReason reason, long activeBefore, long toDeactivate, double missingRatio)
			implements BrakeDecision {
	}
}
