package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.CreateEmployee;
import com.moveinsync.rostersync.domain.DeactivateEmployee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EventHeader;
import com.moveinsync.rostersync.domain.HrEvent;
import com.moveinsync.rostersync.domain.HrOwnedFields;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.UpdateEmployee;
import java.time.Instant;

/**
 * One record exactly as it arrived, from the API or the nightly file, before any
 * checks. Anything may be missing or malformed; validators decide. Only a record with
 * no validation errors may be turned into an {@link HrEvent}.
 */
public record IncomingHrRecord(
		HrEventType type,
		String employeeId,
		Long version,
		Instant occurredAt,
		String firstName,
		String lastName,
		String email,
		String phone,
		String managerId,
		String officeCode,
		String shiftCode) {

	/**
	 * Create and update always carry the full HR state. A deactivation may carry it or
	 * send only the header; if it sends any HR field, it must send a complete, valid set.
	 */
	public boolean carriesFields() {
		return type != HrEventType.DEACTIVATE || hasAnyHrField();
	}

	private boolean hasAnyHrField() {
		return firstName != null || lastName != null || email != null || phone != null || managerId != null
				|| officeCode != null || shiftCode != null;
	}

	public HrEvent toEvent(TenantId tenantId, String sourceSystem) {
		EventHeader header = new EventHeader(tenantId, sourceSystem, new EmployeeId(employeeId), version, occurredAt);
		return switch (type) {
			case CREATE -> new CreateEmployee(header, fields());
			case UPDATE -> new UpdateEmployee(header, fields());
			case DEACTIVATE -> new DeactivateEmployee(header, carriesFields() ? fields() : null);
		};
	}

	private HrOwnedFields fields() {
		return new HrOwnedFields(firstName, lastName, email, phone, managerId, officeCode, shiftCode);
	}

	@Override
	public String toString() {
		return "IncomingHrRecord[type=" + type + ", employeeId=" + employeeId + ", version=" + version + "]";
	}
}
