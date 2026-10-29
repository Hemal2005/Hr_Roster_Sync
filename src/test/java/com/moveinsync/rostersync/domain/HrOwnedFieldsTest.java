package com.moveinsync.rostersync.domain;

import static com.moveinsync.rostersync.domain.TestEvents.EMAIL;
import static com.moveinsync.rostersync.domain.TestEvents.FIRST_NAME;
import static com.moveinsync.rostersync.domain.TestEvents.LAST_NAME;
import static com.moveinsync.rostersync.domain.TestEvents.PHONE;
import static com.moveinsync.rostersync.domain.TestEvents.fields;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HrOwnedFieldsTest {

	@Test
	void groupsWithTheSameValuesAreEqual() {
		assertThat(fields("BLR-01", "MORNING")).isEqualTo(fields("BLR-01", "MORNING"));
		assertThat(fields("BLR-01", "MORNING")).hasSameHashCodeAs(fields("BLR-01", "MORNING"));
		assertThat(fields("BLR-01", "MORNING")).isNotEqualTo(fields("BLR-01", "NIGHT"));
	}

	@Test
	void changedFieldsListsOnlyDifferingFields() {
		var before = fields("BLR-01", "MORNING");
		var after = fields("HYD-02", "NIGHT");

		assertThat(after.changedFieldsSince(before))
				.containsExactlyInAnyOrder(EmployeeField.OFFICE_CODE, EmployeeField.SHIFT_CODE);
		assertThat(after.changedFieldsSince(after)).isEmpty();
	}

	@Test
	void changedFieldsWithoutPreviousListsEveryPresentField() {
		var noManager = new HrOwnedFields(FIRST_NAME, LAST_NAME, EMAIL, PHONE, null, "BLR-01", "MORNING");

		assertThat(noManager.changedFieldsSince(null)).doesNotContain(EmployeeField.MANAGER_ID).hasSize(6);
	}

	@Test
	void ownerIsHr() {
		assertThat(fields("BLR-01", "MORNING").owner()).isEqualTo(FieldOwner.HR);
	}

	@Test
	void toStringListsFieldNamesButNoValues() {
		assertThat(fields("BLR-01", "MORNING").toString())
				.contains("EMAIL", "PHONE")
				.doesNotContain(FIRST_NAME, LAST_NAME, EMAIL, PHONE);
	}
}
