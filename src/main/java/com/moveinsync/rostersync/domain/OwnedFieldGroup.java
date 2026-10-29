package com.moveinsync.rostersync.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A set of employee attribute values that all belong to one owner. The constructor
 * refuses a field owned by someone else, so a group can never carry a foreign value.
 * toString lists field names only: values are personal data.
 */
public abstract sealed class OwnedFieldGroup permits HrOwnedFields, TransportOwnedFields {

	private final FieldOwner owner;
	private final Map<EmployeeField, String> values;

	protected OwnedFieldGroup(FieldOwner owner, Map<EmployeeField, String> values) {
		for (EmployeeField field : values.keySet()) {
			if (field.owner() != owner) {
				throw new IllegalArgumentException(field + " is not owned by " + owner);
			}
		}
		EnumMap<EmployeeField, String> copy = new EnumMap<>(EmployeeField.class);
		copy.putAll(values);
		this.owner = owner;
		this.values = Collections.unmodifiableMap(copy);
	}

	public final FieldOwner owner() {
		return owner;
	}

	protected final String value(EmployeeField field) {
		return values.get(field);
	}

	/**
	 * Names of the fields whose value differs from {@code previous}; every non-null
	 * field when there is no previous group.
	 */
	public final Set<EmployeeField> changedFieldsSince(OwnedFieldGroup previous) {
		if (previous != null && previous.getClass() != getClass()) {
			throw new IllegalArgumentException("cannot compare " + getClass().getSimpleName()
					+ " with " + previous.getClass().getSimpleName());
		}
		Set<EmployeeField> changed = EnumSet.noneOf(EmployeeField.class);
		for (Map.Entry<EmployeeField, String> entry : values.entrySet()) {
			String before = previous == null ? null : previous.values.get(entry.getKey());
			if (!Objects.equals(before, entry.getValue())) {
				changed.add(entry.getKey());
			}
		}
		return changed;
	}

	@Override
	public final boolean equals(Object other) {
		return other instanceof OwnedFieldGroup group
				&& group.getClass() == getClass()
				&& group.values.equals(values);
	}

	@Override
	public final int hashCode() {
		return Objects.hash(getClass(), values);
	}

	@Override
	public final String toString() {
		return getClass().getSimpleName() + values.keySet();
	}
}
