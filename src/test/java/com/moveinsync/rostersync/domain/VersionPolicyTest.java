package com.moveinsync.rostersync.domain;

import static com.moveinsync.rostersync.domain.TestEvents.create;
import static com.moveinsync.rostersync.domain.TestEvents.hash;
import static com.moveinsync.rostersync.domain.TestEvents.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionPolicyTest {

	private final VersionPolicy policy = new VersionPolicy();

	@Test
	void firstEventForAnEmployeeIsApplied() {
		assertThat(policy.decide(Optional.empty(), 1, hash(1))).isEqualTo(VersionDecision.APPLY);
	}

	@ParameterizedTest(name = "stored v{0}, incoming v{1}, same hash {2} -> {3}")
	@CsvSource({
			"5, 6,  false, APPLY",
			"5, 50, false, APPLY",
			"5, 5,  true,  DUPLICATE",
			"5, 5,  false, CONFLICT",
			"5, 4,  true,  STALE",
			"5, 4,  false, STALE",
			"5, 1,  false, STALE"
	})
	void comparesIncomingVersionWithStored(long storedVersion, long incomingVersion, boolean sameHash,
			VersionDecision expected) {
		var stored = Optional.of(new StoredVersion(storedVersion, hash(1)));
		var incomingHash = sameHash ? hash(1) : hash(2);

		assertThat(policy.decide(stored, incomingVersion, incomingHash)).isEqualTo(expected);
	}

	@Test
	void rejectsNonPositiveIncomingVersion() {
		assertThatThrownBy(() -> policy.decide(Optional.empty(), 0, hash(1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void transferV7BeforeShiftV6EndsInNewOfficeWithV7Shift() {
		// Case study example: v6 changed the shift, v7 then moved office. v7 arrives first
		// and already carries the v6 shift because events are full state.
		var v6 = update(6, "BLR-01", "NIGHT");
		var v7 = update(7, "HYD-02", "NIGHT");
		Employee employee = Employee.createFrom(create(5, "BLR-01", "MORNING"), hash(5));

		applyIfAllowed(employee, v7, hash(7));
		VersionDecision v6Decision = applyIfAllowed(employee, v6, hash(6));

		assertThat(v6Decision).isEqualTo(VersionDecision.STALE);
		assertThat(employee.hrVersion()).isEqualTo(7);
		assertThat(employee.hrFields().officeCode()).isEqualTo("HYD-02");
		assertThat(employee.hrFields().shiftCode()).isEqualTo("NIGHT");
	}

	@Test
	void replayingTheSameFeedChangesNothingTheSecondTime() {
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));
		List<UpdateEmployee> feed = List.of(update(2, "BLR-01", "NIGHT"), update(3, "HYD-02", "NIGHT"));

		for (var event : feed) {
			applyIfAllowed(employee, event, hash((int) event.version()));
		}
		long versionAfterFirstRun = employee.hrVersion();
		var fieldsAfterFirstRun = employee.hrFields();

		List<VersionDecision> secondRun = new ArrayList<>();
		for (var event : feed) {
			secondRun.add(applyIfAllowed(employee, event, hash((int) event.version())));
		}

		assertThat(secondRun).containsExactly(VersionDecision.STALE, VersionDecision.DUPLICATE);
		assertThat(employee.hrVersion()).isEqualTo(versionAfterFirstRun);
		assertThat(employee.hrFields()).isEqualTo(fieldsAfterFirstRun);
	}

	@Test
	void anyArrivalOrderConvergesOnTheHighestVersion() {
		List<Long> versions = new ArrayList<>();
		for (long v = 2; v <= 200; v++) {
			versions.add(v);
		}
		Collections.shuffle(versions, new Random(42));
		Employee employee = Employee.createFrom(create(1, "BLR-01", "MORNING"), hash(1));

		for (long v : versions) {
			applyIfAllowed(employee, update(v, "OFFICE-" + v, "MORNING"), hash((int) v));
		}

		assertThat(employee.hrVersion()).isEqualTo(200);
		assertThat(employee.hrFields().officeCode()).isEqualTo("OFFICE-200");
	}

	private VersionDecision applyIfAllowed(Employee employee, HrEvent event, PayloadHash hash) {
		VersionDecision decision = policy.decide(Optional.of(StoredVersion.of(employee)), event.version(), hash);
		if (decision == VersionDecision.APPLY) {
			employee.applyHr(event, hash);
		}
		return decision;
	}
}
