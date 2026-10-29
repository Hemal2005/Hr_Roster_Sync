package com.moveinsync.rostersync.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 over the HR state an event asserts: status, then every HR-owned field in a
 * fixed order. Create and update with the same fields hash the same (it is the state
 * that matters, not how it was sent); a deactivation hashes status alone. Each value is
 * written as a presence byte plus length plus UTF-8 bytes, so null differs from "" and
 * ("ab","c") differs from ("a","bc").
 *
 * Hashes are stored and compared across releases. Changing this encoding turns every
 * future retry into a VERSION_CONFLICT; {@code PayloadHasherTest} pins it.
 */
public final class PayloadHasher {

	public PayloadHash hash(HrEvent event) {
		return switch (event) {
			case CreateEmployee create -> hashState(EmployeeStatus.ACTIVE, create.fields());
			case UpdateEmployee update -> hashState(EmployeeStatus.ACTIVE, update.fields());
			case DeactivateEmployee deactivate -> hashState(EmployeeStatus.INACTIVE, null);
		};
	}

	private static PayloadHash hashState(EmployeeStatus status, HrOwnedFields fields) {
		MessageDigest digest = sha256();
		write(digest, status.name());
		if (fields != null) {
			write(digest, fields.firstName());
			write(digest, fields.lastName());
			write(digest, fields.email());
			write(digest, fields.phone());
			write(digest, fields.managerId());
			write(digest, fields.officeCode());
			write(digest, fields.shiftCode());
		}
		return new PayloadHash(digest.digest());
	}

	private static void write(MessageDigest digest, String value) {
		if (value == null) {
			digest.update((byte) 0);
			return;
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update((byte) 1);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", e);
		}
	}
}
