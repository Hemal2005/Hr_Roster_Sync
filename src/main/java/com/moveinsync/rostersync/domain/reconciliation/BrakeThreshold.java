package com.moveinsync.rostersync.domain.reconciliation;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Share of active employees that may go missing from one export before the run is held
 * for an operator. Kept as a decimal so that "exactly 30%" compares exactly.
 */
public record BrakeThreshold(BigDecimal value) {

	public static final BrakeThreshold DEFAULT = of("0.30");

	public BrakeThreshold {
		Objects.requireNonNull(value, "value");
		if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("threshold must be in (0, 1]");
		}
	}

	public static BrakeThreshold of(String value) {
		return new BrakeThreshold(new BigDecimal(value));
	}
}
