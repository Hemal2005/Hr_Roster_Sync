package com.moveinsync.rostersync.domain;

import static com.moveinsync.rostersync.domain.TestEvents.create;
import static com.moveinsync.rostersync.domain.TestEvents.deactivate;
import static com.moveinsync.rostersync.domain.TestEvents.hash;
import static com.moveinsync.rostersync.domain.TestEvents.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class FieldOwnershipPolicyTest {

	private static final String PICKUP = "12 Lake View Road, Koramangala";
	private static final String ASSISTANCE = "wheelchair";

	private final FieldOwnershipPolicy policy = new FieldOwnershipPolicy();

	@Test
	void hrOwnsNameEmailManagerAndOfficePlusAssumedPhoneShiftAndStatus() {
		assertThat(EmployeeField.values())
				.filteredOn(field -> field.owner() == FieldOwner.HR)
				.containsExactlyInAnyOrder(EmployeeField.FIRST_NAME, EmployeeField.LAST_NAME, EmployeeField.EMAIL,
						EmployeeField.MANAGER_ID, EmployeeField.OFFICE_CODE, EmployeeField.PHONE,
						EmployeeField.SHIFT_CODE, EmployeeField.STATUS);
	}

	@Test
	void transportOwnsPickupPointAndSpecialAssistance() {
		assertThat(EmployeeField.values())
				.filteredOn(field -> field.owner() == FieldOwner.TRANSPORT)
				.containsExactlyInAnyOrder(EmployeeField.PICKUP_POINT, EmployeeField.SPECIAL_ASSISTANCE);
	}

	@Test
	void hrPayloadWithTransportFieldsHasThoseFieldsFlaggedAsForeign() {
		OwnershipCheck check = policy.check(FieldOwner.HR, Set.of("officeCode", "shiftCode", "pickupPoint"));

		assertThat(check.accepted()).containsExactlyInAnyOrder(EmployeeField.OFFICE_CODE, EmployeeField.SHIFT_CODE);
		assertThat(check.foreign()).containsExactly(EmployeeField.PICKUP_POINT);
		assertThat(check.hasViolations()).isTrue();
	}

	@Test
	void transportWriterCannotTouchHrFields() {
		OwnershipCheck check = policy.check(FieldOwner.TRANSPORT, Set.of("pickupPoint", "officeCode"));

		assertThat(check.accepted()).containsExactly(EmployeeField.PICKUP_POINT);
		assertThat(check.foreign()).containsExactly(EmployeeField.OFFICE_CODE);
	}

	@Test
	void unknownNamesAreReportedSeparately() {
		OwnershipCheck check = policy.check(FieldOwner.HR, Set.of("email", "favouriteColour"));

		assertThat(check.accepted()).containsExactly(EmployeeField.EMAIL);
		assertThat(check.unknown()).containsExactly("favouriteColour");
		assertThat(check.hasViolations()).isFalse();
	}

	@Test
	void comparingGroupsOfDifferentOwnersIsRefused() {
		assertThatThrownBy(() -> TestEvents.fields("BLR-01", "MORNING").changedFieldsSince(TransportOwnedFields.NONE))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hrUpdatesNeverChangeTransportFields() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));
		employee.applyTransport(new TransportOwnedFields(PICKUP, ASSISTANCE), 0);

		employee.applyHr(update(2, "HYD-02", "NIGHT"), hash(2));
		employee.applyHr(deactivate(3), hash(3));
		employee.applyHr(update(4, "HYD-02", "NIGHT"), hash(4));

		assertThat(employee.transportFields().pickupPoint()).isEqualTo(PICKUP);
		assertThat(employee.transportFields().specialAssistance()).isEqualTo(ASSISTANCE);
		assertThat(employee.transportVersion()).isEqualTo(1);
	}

	@Test
	void transportEditsDoNotMoveTheHrVersion() {
		Employee employee = Employee.createFrom(create(5, "BLR-01", "MORNING"), hash(5));

		employee.applyTransport(new TransportOwnedFields(PICKUP, null), 0);

		assertThat(employee.hrVersion()).isEqualTo(5);
		assertThat(employee.transportVersion()).isEqualTo(1);
	}

	@Test
	void transportEditWithAnOutdatedExpectedVersionIsRefused() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));
		employee.applyTransport(new TransportOwnedFields(PICKUP, null), 0);

		assertThatThrownBy(() -> employee.applyTransport(new TransportOwnedFields("elsewhere", null), 0))
				.isInstanceOf(DomainException.class)
				.extracting(e -> ((DomainException) e).code())
				.isEqualTo(ErrorCode.TRANSPORT_VERSION_MISMATCH);
		assertThat(employee.transportFields().pickupPoint()).isEqualTo(PICKUP);
	}

	@Test
	void transportEditReportsChangedFieldNames() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));

		assertThat(employee.applyTransport(new TransportOwnedFields(PICKUP, null), 0))
				.containsExactly(EmployeeField.PICKUP_POINT);
	}

	@Test
	void transportValuesNeverAppearInToString() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));
		employee.applyTransport(new TransportOwnedFields(PICKUP, ASSISTANCE), 0);

		assertThat(employee.toString()).doesNotContain(PICKUP, ASSISTANCE);
		assertThat(employee.transportFields().toString()).doesNotContain(PICKUP, ASSISTANCE);
	}
}
