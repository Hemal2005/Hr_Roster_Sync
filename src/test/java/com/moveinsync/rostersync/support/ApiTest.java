package com.moveinsync.rostersync.support;

import com.moveinsync.rostersync.api.security.JwtSettings;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for HTTP-level tests. Requests go through the real filter chain, and tokens are
 * signed with the test key so the real decoder verifies them.
 */
@AutoConfigureMockMvc
public abstract class ApiTest extends IntegrationTest {

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected ObjectMapper json;

	@Autowired
	private JwtSettings jwtSettings;

	protected String bearer(String tenantId, String subject, String... roles) {
		return "Bearer " + token(jwtSettings.key().getEncoded(), tenantId, subject, Duration.ofHours(1), roles);
	}

	protected String bearerSignedWith(byte[] key, String tenantId, String... roles) {
		return "Bearer " + token(key, tenantId, "tester", Duration.ofHours(1), roles);
	}

	protected String expiredBearer(String tenantId, String... roles) {
		return "Bearer " + token(jwtSettings.key().getEncoded(), tenantId, "tester", Duration.ofHours(-1), roles);
	}

	private static String token(byte[] key, String tenantId, String subject, Duration validFor, String... roles) {
		JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.subject(subject)
				.issuedAt(validFor.isNegative() ? now.plus(validFor).minusSeconds(60) : now)
				.expiresAt(now.plus(validFor))
				.claim("roles", List.of(roles));
		if (tenantId != null) {
			claims.claim("tenant_id", tenantId);
		}
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
				.getTokenValue();
	}

	protected String batch(List<Map<String, Object>> events) {
		return json.writeValueAsString(Map.of("sourceSystem", "workday", "events", events));
	}
}
