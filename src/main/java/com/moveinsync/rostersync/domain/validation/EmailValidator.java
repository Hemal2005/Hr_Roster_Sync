package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Structural check only (one @, no whitespace, a dot in the domain, at most 254 chars).
 * Whether the mailbox exists is HR's concern, not ours.
 */
public final class EmailValidator implements RecordValidator {

	private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final int MAX_LENGTH = 254;

	@Override
	public List<ValidationError> validate(TenantId tenantId, IncomingHrRecord record) {
		String email = record.email();
		if (!record.carriesFields() || email == null || email.isBlank()) {
			return List.of();
		}
		if (email.length() > MAX_LENGTH || !SHAPE.matcher(email).matches()) {
			return List.of(new ValidationError(ErrorCode.INVALID_EMAIL, "email"));
		}
		return List.of();
	}
}
