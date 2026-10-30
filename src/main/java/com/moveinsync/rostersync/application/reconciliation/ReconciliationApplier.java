package com.moveinsync.rostersync.application.reconciliation;

import com.moveinsync.rostersync.application.ApplyOutcome;
import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.application.ReconciliationSource;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore.StoredItem;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction;
import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies a stored plan through the same paths as live HR events. Creates and updates go
 * through {@link ApplyService#apply}, so the version rule decides as it always does: a
 * live change applied after the export was taken wins. Deactivations go through
 * {@link ApplyService#deactivateMissing}, which skips anyone who changed since planning.
 */
@Component
public class ReconciliationApplier {

	public record Outcome(long applied, long skipped) {
	}

	private static final int PAGE = 500;

	private final ReconciliationStore store;
	private final ApplyService applyService;
	private final ObjectMapper json;

	ReconciliationApplier(ReconciliationStore store, ApplyService applyService, ObjectMapper json) {
		this.store = store;
		this.applyService = applyService;
		this.json = json;
	}

	public Outcome apply(UUID runId, TenantId tenantId, Instant asOf) {
		long applied = 0;
		long skipped = 0;
		long afterId = 0;
		List<StoredItem> page;
		do {
			page = store.items(runId, afterId, PAGE);
			for (StoredItem item : page) {
				boolean done = item.action() == ReconciliationAction.DEACTIVATE
						? applyService.deactivateMissing(tenantId, new EmployeeId(item.employeeId()), item.rowVersion(), asOf)
						: applyService.apply(tenantId, ReconciliationSource.NAME, toRecord(item, asOf)).outcome()
								== ApplyOutcome.APPLIED;
				if (done) {
					applied++;
				} else {
					skipped++;
				}
				afterId = item.id();
			}
		} while (page.size() == PAGE);
		return new Outcome(applied, skipped);
	}

	private IncomingHrRecord toRecord(StoredItem item, Instant asOf) {
		JsonNode p = json.readTree(item.payload());
		HrEventType type = "INACTIVE".equals(text(p, "status")) ? HrEventType.DEACTIVATE : HrEventType.UPDATE;
		return new IncomingHrRecord(type, item.employeeId(), item.rowVersion(), asOf, text(p, "firstName"),
				text(p, "lastName"), text(p, "email"), text(p, "phone"), text(p, "managerId"), text(p, "officeCode"),
				text(p, "shiftCode"));
	}

	private static String text(JsonNode payload, String field) {
		JsonNode value = payload.get(field);
		return value == null || value.isNull() ? null : value.stringValue();
	}
}
