package com.moveinsync.rostersync.domain.reconciliation;

import java.util.List;

/**
 * The diff of one bucket: planned changes, plus employees whose export row has the same
 * version as the store but different content (reported, never applied).
 */
public record BucketPlan(List<PlanItem> items, List<String> conflicts) {

	public BucketPlan {
		items = List.copyOf(items);
		conflicts = List.copyOf(conflicts);
	}

	public long count(ReconciliationAction action) {
		return items.stream().filter(item -> item.action() == action).count();
	}
}
