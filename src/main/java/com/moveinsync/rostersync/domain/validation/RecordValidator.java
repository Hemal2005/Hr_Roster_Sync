package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.TenantId;
import java.util.List;

/**
 * One rule applied to an incoming record. An empty list means the rule passed.
 * Validators only report on fields they own and skip absent values, leaving
 * "is it present?" to {@link RequiredFieldsValidator}.
 */
public interface RecordValidator {

	List<ValidationError> validate(TenantId tenantId, IncomingHrRecord record);
}
