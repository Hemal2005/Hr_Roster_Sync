package com.moveinsync.rostersync.domain;

import static com.moveinsync.rostersync.domain.TestEvents.EMAIL;
import static com.moveinsync.rostersync.domain.TestEvents.EMPLOYEE;
import static com.moveinsync.rostersync.domain.TestEvents.PHONE;
import static com.moveinsync.rostersync.domain.TestEvents.SOURCE;
import static com.moveinsync.rostersync.domain.TestEvents.T0;
import static com.moveinsync.rostersync.domain.TestEvents.TENANT;
import static com.moveinsync.rostersync.domain.TestEvents.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HrEventTest {

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void versionMustBePositive(long version) {
		assertThatThrownBy(() -> new EventHeader(TENANT, SOURCE, EMPLOYEE, version, T0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sourceSystemMustNotBeBlank() {
		assertThatThrownBy(() -> new EventHeader(TENANT, " ", EMPLOYEE, 1, T0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void idsMustNotBeBlank() {
		assertThatThrownBy(() -> new TenantId("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EmployeeId(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void eventExposesHeaderValuesDirectly() {
		HrEvent event = update(7, "HYD-02", "NIGHT");

		assertThat(event.version()).isEqualTo(7);
		assertThat(event.employeeId()).isEqualTo(EMPLOYEE);
		assertThat(event.tenantId()).isEqualTo(TENANT);
	}

	@Test
	void toStringContainsNoPersonalData() {
		assertThat(update(7, "HYD-02", "NIGHT").toString())
				.contains("E000042")
				.doesNotContain(EMAIL, PHONE);
	}
}
