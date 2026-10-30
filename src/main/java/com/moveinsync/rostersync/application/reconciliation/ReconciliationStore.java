package com.moveinsync.rostersync.application.reconciliation;

import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.PlanItem;
import com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction;
import com.moveinsync.rostersync.domain.reconciliation.StoredEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for reconciliation runs, their planned changes and their per-line errors,
 * plus the per-bucket view of stored employees that planning compares against.
 */
public interface ReconciliationStore {

	record NewItem(PlanItem item, String payload) {
	}

	record StoredItem(long id, ReconciliationAction action, String employeeId, long rowVersion, String payload) {
	}

	record RunError(long lineNo, String employeeId, ErrorCode code) {
	}

	void createRun(UUID runId, TenantId tenantId, String fileName, Instant asOf, String submittedBy);

	Optional<ReconciliationRun> find(TenantId tenantId, UUID runId);

	/** One bucket of the tenant's employees; ids, versions, hashes and status only. */
	List<StoredEntry> storedEntries(TenantId tenantId, int bucket);

	void addItems(UUID runId, List<NewItem> items);

	void addErrors(UUID runId, List<RunError> errors);

	void recordPlan(UUID runId, PlanSummary summary);

	/** Changes status only if it is still {@code from}; returns false otherwise. */
	boolean transition(UUID runId, RunStatus from, RunStatus to);

	void fail(UUID runId, String failureCode);

	/** Items after {@code afterId}, in id order, at most {@code limit}. */
	List<StoredItem> items(UUID runId, long afterId, int limit);

	List<RunError> errors(TenantId tenantId, UUID runId, int page, int pageSize);
}
