package com.moveinsync.rostersync.domain.reconciliation;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Holds a reconciliation plan for a human when the export looks broken.
 *
 * <pre>
 * trip if  trailer missing or count wrong                        (FILE_TRUNCATED)
 *     or   toDeactivate / activeBefore &gt;= threshold              (MISSING_RATIO_EXCEEDED)
 * </pre>
 *
 * The denominator is the number of employees active in the store before the run, not
 * the rows in the file: the rule is about the share of active employees that would
 * disappear, and the file under suspicion cannot be its own baseline. A truncated file
 * trips the brake whatever the ratio, because a file that stopped early cannot be
 * trusted for its creates and updates either.
 */
public final class SafetyBrake {

	public BrakeDecision evaluate(long activeBefore, long toDeactivate, boolean trailerOk, BrakeThreshold threshold) {
		Objects.requireNonNull(threshold, "threshold");
		if (activeBefore < 0 || toDeactivate < 0) {
			throw new IllegalArgumentException("counts must not be negative");
		}
		if (toDeactivate > activeBefore) {
			throw new IllegalArgumentException("cannot deactivate more employees than are active");
		}
		double ratio = activeBefore == 0 ? 0.0 : (double) toDeactivate / activeBefore;
		if (!trailerOk) {
			return new BrakeDecision.Trip(BrakeReason.FILE_TRUNCATED, activeBefore, toDeactivate, ratio);
		}
		if (activeBefore > 0 && reachesThreshold(activeBefore, toDeactivate, threshold)) {
			return new BrakeDecision.Trip(BrakeReason.MISSING_RATIO_EXCEEDED, activeBefore, toDeactivate, ratio);
		}
		return new BrakeDecision.Pass(activeBefore, toDeactivate, ratio);
	}

	private static boolean reachesThreshold(long activeBefore, long toDeactivate, BrakeThreshold threshold) {
		// toDeactivate >= threshold * activeBefore, without floating-point rounding
		BigDecimal limit = threshold.value().multiply(BigDecimal.valueOf(activeBefore));
		return BigDecimal.valueOf(toDeactivate).compareTo(limit) >= 0;
	}
}
