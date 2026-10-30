package com.moveinsync.rostersync.api.security;

/**
 * Roles carried in the token's {@code roles} claim. HR_INTEGRATION is the HR system
 * itself: a machine client that pushes events and exports, not a person.
 */
public enum Role {
	EMPLOYEE,
	ADMIN,
	OPERATOR,
	HR_INTEGRATION
}
