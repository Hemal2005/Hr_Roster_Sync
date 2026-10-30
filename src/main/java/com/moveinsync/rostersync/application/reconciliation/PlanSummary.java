package com.moveinsync.rostersync.application.reconciliation;

/**
 * What planning found. {@code activeBefore} is counted before the file is read and is the
 * safety brake's denominator.
 */
public record PlanSummary(long rowsRead, long rowsInvalid, long toCreate, long toUpdate, long toDeactivate,
		long conflicts, long activeBefore, Long trailerCount, boolean trailerOk) {
}
