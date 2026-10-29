package com.moveinsync.rostersync.domain.validation;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Requires canonical E.164 ("+" then country code and number, digits only) and a number
 * that libphonenumber considers valid for its country. Formatted input such as
 * "+91 98765 43210" is rejected rather than normalised: the stored value feeds the
 * payload hash, so accepting several spellings would make equal records look different.
 */
public final class PhoneValidator implements RecordValidator {

	private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
	private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

	@Override
	public List<ValidationError> validate(TenantId tenantId, IncomingHrRecord record) {
		String phone = record.phone();
		if (!record.carriesFields() || phone == null || phone.isBlank()) {
			return List.of();
		}
		if (!E164.matcher(phone).matches() || !isValidNumber(phone)) {
			return List.of(new ValidationError(ErrorCode.INVALID_PHONE, "phone"));
		}
		return List.of();
	}

	private static boolean isValidNumber(String phone) {
		try {
			return PHONE_NUMBERS.isValidNumber(PHONE_NUMBERS.parse(phone, null));
		} catch (NumberParseException e) {
			return false;
		}
	}
}
