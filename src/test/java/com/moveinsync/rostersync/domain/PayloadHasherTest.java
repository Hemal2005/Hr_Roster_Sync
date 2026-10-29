package com.moveinsync.rostersync.domain;

import static com.moveinsync.rostersync.domain.TestEvents.create;
import static com.moveinsync.rostersync.domain.TestEvents.deactivate;
import static com.moveinsync.rostersync.domain.TestEvents.header;
import static com.moveinsync.rostersync.domain.TestEvents.update;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayloadHasherTest {

	private final PayloadHasher hasher = new PayloadHasher();

	@Test
	void encodingIsPinnedSoStoredHashesStayComparableAcrossReleases() {
		// Expected values computed independently (Python hashlib) from the documented encoding.
		assertThat(hasher.hash(update(7, "BLR-01", "MORNING")).hex())
				.isEqualTo("d0aec431efc2c4778ac0a2762e921f015cae0a0bb826325258e8dcb30c9cffec");
		assertThat(hasher.hash(deactivate(8)).hex())
				.isEqualTo("0735d8eee5502a58f21ad42ea0cfc53d94b990aa95585585623c13ed2ef149a8");
	}

	@Test
	void sameStateGivesSameHash() {
		assertThat(hasher.hash(update(7, "BLR-01", "MORNING"))).isEqualTo(hasher.hash(update(7, "BLR-01", "MORNING")));
	}

	@Test
	void versionAndTimestampAreNotPartOfTheHash() {
		assertThat(hasher.hash(update(7, "BLR-01", "MORNING"))).isEqualTo(hasher.hash(update(9, "BLR-01", "MORNING")));
	}

	@Test
	void createAndUpdateOfTheSameStateHashTheSame() {
		assertThat(hasher.hash(create(1, "BLR-01", "MORNING"))).isEqualTo(hasher.hash(update(1, "BLR-01", "MORNING")));
	}

	@Test
	void anyFieldChangeChangesTheHash() {
		assertThat(hasher.hash(update(7, "BLR-01", "MORNING"))).isNotEqualTo(hasher.hash(update(7, "BLR-01", "NIGHT")));
	}

	@Test
	void deactivationHashDiffersFromActiveState() {
		assertThat(hasher.hash(deactivate(7))).isNotEqualTo(hasher.hash(update(7, "BLR-01", "MORNING")));
	}

	@Test
	void nullAndEmptyAreDifferent() {
		var withNullManager = new UpdateEmployee(header(1), new HrOwnedFields("A", "B", "e@x.io", "+1", null, "O", "S"));
		var withEmptyManager = new UpdateEmployee(header(1), new HrOwnedFields("A", "B", "e@x.io", "+1", "", "O", "S"));

		assertThat(hasher.hash(withNullManager)).isNotEqualTo(hasher.hash(withEmptyManager));
	}

	@Test
	void valuesCannotBleedAcrossFieldBoundaries() {
		var ab = new UpdateEmployee(header(1), new HrOwnedFields("ab", "c", "e@x.io", "+1", null, "O", "S"));
		var bc = new UpdateEmployee(header(1), new HrOwnedFields("a", "bc", "e@x.io", "+1", null, "O", "S"));

		assertThat(hasher.hash(ab)).isNotEqualTo(hasher.hash(bc));
	}

	@Test
	void hashIsSha256Length() {
		assertThat(hasher.hash(update(1, "BLR-01", "MORNING")).bytes()).hasSize(32);
	}
}
