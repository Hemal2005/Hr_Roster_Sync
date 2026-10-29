package com.moveinsync.rostersync.domain.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SafetyBrakeTest {

	private final SafetyBrake brake = new SafetyBrake();

	@Test
	void normalNightPasses() {
		BrakeDecision decision = brake.evaluate(200_000, 150, true, BrakeThreshold.DEFAULT);

		assertThat(decision).isInstanceOf(BrakeDecision.Pass.class);
		assertThat(decision.toDeactivate()).isEqualTo(150);
	}

	@Test
	void fileCutAt120kRowsTripsAndReportsAbout80kMissing() {
		// Case study example: 200,000 active, the file stops at row 120,000 with no trailer.
		BrakeDecision decision = brake.evaluate(200_000, 80_000, false, BrakeThreshold.DEFAULT);

		assertThat(decision).isInstanceOf(BrakeDecision.Trip.class);
		assertThat(((BrakeDecision.Trip) decision).reason()).isEqualTo(BrakeReason.FILE_TRUNCATED);
		assertThat(decision.toDeactivate()).isEqualTo(80_000);
		assertThat(decision.activeBefore()).isEqualTo(200_000);
		assertThat(decision.missingRatio()).isEqualTo(0.4);
	}

	@Test
	void missingTrailerTripsEvenWhenNobodyWouldBeDeactivated() {
		BrakeDecision decision = brake.evaluate(200_000, 0, false, BrakeThreshold.DEFAULT);

		assertThat(decision).isInstanceOf(BrakeDecision.Trip.class);
		assertThat(((BrakeDecision.Trip) decision).reason()).isEqualTo(BrakeReason.FILE_TRUNCATED);
	}

	@ParameterizedTest(name = "{1} of {0} missing -> trips: {2}")
	@CsvSource({
			"100000, 29999, false",
			"100000, 30000, true",
			"100000, 30001, true",
			"10,     2,     false",
			"10,     3,     true",
			"3,      1,     true",
			"7,      2,     false"
	})
	void tripsAtOrAboveThirtyPercentOfActiveEmployees(long active, long missing, boolean trips) {
		BrakeDecision decision = brake.evaluate(active, missing, true, BrakeThreshold.DEFAULT);

		assertThat(decision instanceof BrakeDecision.Trip).isEqualTo(trips);
		if (trips) {
			assertThat(((BrakeDecision.Trip) decision).reason()).isEqualTo(BrakeReason.MISSING_RATIO_EXCEEDED);
		}
	}

	@Test
	void thresholdConfigurable() {
		assertThat(brake.evaluate(100, 15, true, BrakeThreshold.of("0.10")))
				.isInstanceOf(BrakeDecision.Trip.class);
		assertThat(brake.evaluate(100, 35, true, BrakeThreshold.of("0.40")))
				.isInstanceOf(BrakeDecision.Pass.class);
		assertThat(brake.evaluate(100, 40, true, BrakeThreshold.of("0.40")))
				.isInstanceOf(BrakeDecision.Trip.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-0.1", "1.01"})
	void thresholdMustBeAFractionAboveZero(String value) {
		assertThatThrownBy(() -> BrakeThreshold.of(value)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void tenantWithNoActiveEmployeesPasses() {
		BrakeDecision decision = brake.evaluate(0, 0, true, BrakeThreshold.DEFAULT);

		assertThat(decision).isInstanceOf(BrakeDecision.Pass.class);
		assertThat(decision.missingRatio()).isZero();
	}

	@Test
	void refusesImpossibleCounts() {
		assertThatThrownBy(() -> brake.evaluate(10, 11, true, BrakeThreshold.DEFAULT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> brake.evaluate(-1, 0, true, BrakeThreshold.DEFAULT))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
