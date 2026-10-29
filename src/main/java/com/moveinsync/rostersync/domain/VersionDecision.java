package com.moveinsync.rostersync.domain;

public enum VersionDecision {
	/** Newer than anything stored: write it. */
	APPLY,
	/** Same version, same content: a retry. Change nothing, publish nothing. */
	DUPLICATE,
	/** Older than what is stored: ignore it. */
	STALE,
	/** Same version, different content: the source contradicted itself. Reject. */
	CONFLICT
}
