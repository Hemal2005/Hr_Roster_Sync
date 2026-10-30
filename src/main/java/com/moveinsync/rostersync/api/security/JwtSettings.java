package com.moveinsync.rostersync.api.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The HMAC key that signs and verifies tokens. There is deliberately no default: an
 * application started without {@code rostersync.security.jwt-secret} fails at startup
 * rather than running with a guessable key. Profiles {@code dev} and {@code test} set
 * local-only values.
 */
@ConfigurationProperties("rostersync.security")
public record JwtSettings(String jwtSecret) {

	private static final int MIN_KEY_BYTES = 32;

	public JwtSettings {
		if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
			throw new IllegalArgumentException(
					"rostersync.security.jwt-secret must be set and at least " + MIN_KEY_BYTES + " bytes");
		}
	}

	public SecretKey key() {
		return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}
}
