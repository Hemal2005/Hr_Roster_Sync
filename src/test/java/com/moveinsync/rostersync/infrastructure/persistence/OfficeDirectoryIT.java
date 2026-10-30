package com.moveinsync.rostersync.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import com.moveinsync.rostersync.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfficeDirectoryIT extends IntegrationTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final TenantId GLOBEX = new TenantId("globex");

	@Autowired
	OfficeDirectory offices;

	@BeforeEach
	void offices() {
		givenTenant("acme", "BLR-01", "HYD-02");
		givenTenant("globex", "PUN-01");
		jdbc.update("UPDATE office SET active = false WHERE tenant_id = 'acme' AND code = 'HYD-02'");
	}

	@Test
	void knownActiveOfficeIsFound() {
		assertThat(offices.isKnownOffice(ACME, "BLR-01")).isTrue();
	}

	@Test
	void unknownOfficeIsNotFound() {
		assertThat(offices.isKnownOffice(ACME, "CHN-99")).isFalse();
	}

	@Test
	void inactiveOfficeCountsAsUnknown() {
		assertThat(offices.isKnownOffice(ACME, "HYD-02")).isFalse();
	}

	@Test
	void anotherTenantsOfficeIsUnknown() {
		assertThat(offices.isKnownOffice(ACME, "PUN-01")).isFalse();
		assertThat(offices.isKnownOffice(GLOBEX, "PUN-01")).isTrue();
	}

	@Test
	void codesAreCaseSensitive() {
		assertThat(offices.isKnownOffice(ACME, "blr-01")).isFalse();
	}
}
