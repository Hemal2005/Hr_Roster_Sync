package com.moveinsync.rostersync.domain.reconciliation;

public enum ReconciliationAction {
	/** In the export, not in the store. */
	CREATE,
	/** In both; the export has a newer version with different content. */
	UPDATE,
	/** Active in the store, absent from the export, and not changed since the export was taken. */
	DEACTIVATE
}
