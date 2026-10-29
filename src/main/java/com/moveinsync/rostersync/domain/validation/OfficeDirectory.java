package com.moveinsync.rostersync.domain.validation;

import com.moveinsync.rostersync.domain.TenantId;

/**
 * Lookup of a tenant's office codes. Implemented outside the domain (database plus
 * cache); the domain only needs the question answered.
 */
public interface OfficeDirectory {

	boolean isKnownOffice(TenantId tenantId, String officeCode);
}
