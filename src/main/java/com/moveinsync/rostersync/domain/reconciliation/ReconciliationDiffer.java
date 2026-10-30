package com.moveinsync.rostersync.domain.reconciliation;

import com.moveinsync.rostersync.domain.EmployeeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares one hash bucket of the nightly export with the same bucket of the store. Only
 * one bucket is ever in memory, so memory stays bounded however large the export is.
 *
 * <pre>
 * stored, not in file    active and last HR change &lt;= as_of  -&gt; DEACTIVATE
 *                        changed after as_of (live event wins)  -&gt; nothing
 * stored, invalid row    present but unusable                   -&gt; nothing (never deactivated)
 * stored, in file        file version &gt; stored, content differs -&gt; UPDATE
 *                        same version, content differs          -&gt; conflict (reported)
 *                        otherwise                              -&gt; nothing
 * file only, valid                                              -&gt; CREATE
 * </pre>
 *
 * Planned changes are applied later through the normal version rule, so a snapshot can
 * never overwrite a newer live change even if one arrives between plan and apply.
 */
public final class ReconciliationDiffer {

	public BucketPlan diff(Map<String, FileEntry> file, Iterable<StoredEntry> stored, Instant asOf) {
		Objects.requireNonNull(asOf, "asOf");
		List<PlanItem> items = new ArrayList<>();
		List<String> conflicts = new ArrayList<>();
		Set<String> storedIds = new HashSet<>();

		for (StoredEntry s : stored) {
			storedIds.add(s.employeeId());
			FileEntry f = file.get(s.employeeId());
			if (f == null) {
				if (s.status() == EmployeeStatus.ACTIVE && !s.lastHrEventAt().isAfter(asOf)) {
					items.add(new PlanItem(ReconciliationAction.DEACTIVATE, s.employeeId(), s.hrVersion()));
				}
			} else if (f.valid()) {
				boolean sameContent = f.hashHex().equals(s.hashHex());
				if (f.version() > s.hrVersion() && !sameContent) {
					items.add(new PlanItem(ReconciliationAction.UPDATE, s.employeeId(), f.version()));
				} else if (f.version() == s.hrVersion() && !sameContent) {
					conflicts.add(s.employeeId());
				}
			}
		}

		List<PlanItem> creates = new ArrayList<>();
		for (FileEntry f : file.values()) {
			if (f.valid() && !storedIds.contains(f.employeeId())) {
				creates.add(new PlanItem(ReconciliationAction.CREATE, f.employeeId(), f.version()));
			}
		}
		creates.sort(Comparator.comparing(PlanItem::employeeId));
		items.addAll(creates);
		return new BucketPlan(items, conflicts);
	}
}
