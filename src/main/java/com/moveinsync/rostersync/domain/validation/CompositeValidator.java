package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.TenantId;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every validator and returns all errors in validator order, so a sender fixing a
 * record sees every problem at once rather than one per retry.
 */
public final class CompositeValidator implements RecordValidator {

	private final List<RecordValidator> validators;

	public CompositeValidator(List<RecordValidator> validators) {
		this.validators = List.copyOf(validators);
	}

	public static CompositeValidator standard(OfficeDirectory offices) {
		return new CompositeValidator(List.of(
				new RequiredFieldsValidator(),
				new EmailValidator(),
				new PhoneValidator(),
				new OfficeCodeValidator(offices)));
	}

	@Override
	public List<ValidationError> validate(TenantId tenantId, IncomingHrRecord record) {
		List<ValidationError> errors = new ArrayList<>();
		for (RecordValidator validator : validators) {
			errors.addAll(validator.validate(tenantId, record));
		}
		return List.copyOf(errors);
	}
}
