package com.moveinsync.rostersync.domain;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The conflict rule between HR and the transport admin:
 * <ol>
 * <li>every field has exactly one owner ({@link EmployeeField#owner()});</li>
 * <li>only the owner's write path changes it, and each owner has its own version;</li>
 * <li>a value for a field the writer does not own is ignored and counted, and the rest
 * of the record is still applied;</li>
 * <li>there is no last-write-wins across owners.</li>
 * </ol>
 * The write paths enforce rule 2 by construction (the HR path only accepts
 * {@link HrOwnedFields}); this class tells the boundary which submitted names to drop
 * and count.
 */
public final class FieldOwnershipPolicy {

	public OwnershipCheck check(FieldOwner writer, Set<String> submittedFieldNames) {
		Set<EmployeeField> accepted = EnumSet.noneOf(EmployeeField.class);
		Set<EmployeeField> foreign = EnumSet.noneOf(EmployeeField.class);
		Set<String> unknown = new HashSet<>();
		for (String name : submittedFieldNames) {
			Optional<EmployeeField> field = EmployeeField.fromWireName(name);
			if (field.isEmpty()) {
				unknown.add(name);
			} else if (field.get().owner() == writer) {
				accepted.add(field.get());
			} else {
				foreign.add(field.get());
			}
		}
		return new OwnershipCheck(accepted, foreign, unknown);
	}
}
