package com.moveinsync.rostersync.application.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A run as an operator sees it: counts and decisions, no employee data.
 */
public record ReconciliationRun(
		UUID id,
		String tenantId,
		String fileName,
		Instant asOf,
		RunStatus status,
		String submittedBy,
		long rowsRead,
		long rowsInvalid,
		long toCreate,
		long toUpdate,
		long toDeactivate,
		long conflicts,
		long activeBefore,
		BigDecimal missingRatio,
		Long trailerCount,
		Boolean trailerOk,
		Boolean brakeTripped,
		String brakeReason,
		long applied,
		long skipped,
		String decidedBy,
		String failureCode,
		Instant createdAt,
		Instant plannedAt,
		Instant decidedAt,
		Instant completedAt) {
}
