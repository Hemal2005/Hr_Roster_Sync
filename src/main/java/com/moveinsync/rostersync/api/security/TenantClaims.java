package com.moveinsync.rostersync.api.security;

import com.moveinsync.rostersync.domain.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The tenant comes from the verified token and from nowhere else: not the path, not the
 * body, not a header.
 */
public final class TenantClaims {

	public static final String TENANT_ID = "tenant_id";

	private TenantClaims() {
	}

	public static TenantId tenantOf(Jwt jwt) {
		return new TenantId(jwt.getClaimAsString(TENANT_ID));
	}
}
