package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import java.util.ArrayList;
import java.util.List;

/**
 * Every record needs type, employee id, a positive version and the time of the change.
 * Create and update also need the fields a cab booking cannot work without. Last name
 * (mononyms) and manager (top of the hierarchy) are optional.
 */
public final class RequiredFieldsValidator implements RecordValidator {

	@Override
	public List<ValidationError> validate(TenantId tenantId, IncomingHrRecord record) {
		List<ValidationError> errors = new ArrayList<>();
		requirePresent(record.type(), "type", errors);
		requireText(record.employeeId(), "employeeId", errors);
		requirePresent(record.occurredAt(), "occurredAt", errors);
		if (record.version() == null) {
			errors.add(new ValidationError(ErrorCode.MISSING_REQUIRED_FIELD, "version"));
		} else if (record.version() < 1) {
			errors.add(new ValidationError(ErrorCode.INVALID_VERSION, "version"));
		}
		if (record.type() != null && record.carriesFields()) {
			requireText(record.firstName(), "firstName", errors);
			requireText(record.email(), "email", errors);
			requireText(record.phone(), "phone", errors);
			requireText(record.officeCode(), "officeCode", errors);
			requireText(record.shiftCode(), "shiftCode", errors);
		}
		return errors;
	}

	private static void requirePresent(Object value, String field, List<ValidationError> errors) {
		if (value == null) {
			errors.add(new ValidationError(ErrorCode.MISSING_REQUIRED_FIELD, field));
		}
	}

	private static void requireText(String value, String field, List<ValidationError> errors) {
		if (value == null || value.isBlank()) {
			errors.add(new ValidationError(ErrorCode.MISSING_REQUIRED_FIELD, field));
		}
	}
}
