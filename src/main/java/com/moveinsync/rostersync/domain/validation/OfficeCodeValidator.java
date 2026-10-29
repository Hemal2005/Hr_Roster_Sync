package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import java.util.List;
import java.util.Objects;

/**
 * Office codes are per tenant: a code valid for one client can be unknown for another.
 */
public final class OfficeCodeValidator implements RecordValidator {

	private final OfficeDirectory offices;

	public OfficeCodeValidator(OfficeDirectory offices) {
		this.offices = Objects.requireNonNull(offices, "offices");
	}

	@Override
	public List<ValidationError> validate(TenantId tenantId, IncomingHrRecord record) {
		String officeCode = record.officeCode();
		if (!record.carriesFields() || officeCode == null || officeCode.isBlank()) {
			return List.of();
		}
		if (!offices.isKnownOffice(tenantId, officeCode)) {
			return List.of(new ValidationError(ErrorCode.UNKNOWN_OFFICE, "officeCode"));
		}
		return List.of();
	}
}
