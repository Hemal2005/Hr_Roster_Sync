package com.moveinsync.rostersync.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues tokens for local evaluation only. The bean exists only under the dev and demo
 * profiles; with any other profile the path has no handler and answers 404.
 */
@RestController
@Profile({"dev", "demo"})
class DevTokenController {

	private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);
	private static final Duration VALIDITY = Duration.ofHours(8);

	private final JwtEncoder encoder;

	DevTokenController(JwtSettings settings) {
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(settings.key()));
		log.warn("POST /dev/token is enabled; this profile is for local evaluation only");
	}

	record DevTokenRequest(@NotBlank String tenantId, @NotBlank String subject, @NotEmpty List<Role> roles) {
	}

	record DevTokenResponse(String token, Instant expiresAt) {
	}

	@PostMapping(path = "/dev/token", consumes = MediaType.APPLICATION_JSON_VALUE)
	DevTokenResponse issue(@Valid @RequestBody DevTokenRequest request) {
		Instant now = Instant.now();
		Instant expiresAt = now.plus(VALIDITY);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(request.subject())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.claim(TenantClaims.TENANT_ID, request.tenantId())
				.claim(SecurityConfig.ROLES_CLAIM, request.roles().stream().map(Role::name).toList())
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new DevTokenResponse(token, expiresAt);
	}
}
