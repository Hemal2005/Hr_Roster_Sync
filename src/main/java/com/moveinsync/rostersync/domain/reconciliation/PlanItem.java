package com.moveinsync.rostersync.domain.reconciliation;

import java.util.Objects;

/**
 * One planned change. {@code version} is the export's version for CREATE and UPDATE, and
 * the stored version for DEACTIVATE: applying it later checks the row has not moved on.
 */
public record PlanItem(ReconciliationAction action, String employeeId, long version) {

	public PlanItem {
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(employeeId, "employeeId");
	}
}
