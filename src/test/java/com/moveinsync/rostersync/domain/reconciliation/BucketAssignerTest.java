package com.moveinsync.rostersync.domain.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.rostersync.domain.EmployeeId;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BucketAssignerTest {

	private final BucketAssigner assigner = new BucketAssigner(BucketAssigner.DEFAULT_BUCKET_COUNT);

	@ParameterizedTest(name = "murmur3(\"{0}\", seed {1}) = {2}")
	@CsvSource({
			"'',                                          0x00000000, 0x00000000",
			"'',                                          0x00000001, 0x514E28B7",
			"'',                                          0xFFFFFFFF, 0x81F16F39",
			"test,                                        0x00000000, 0xBA6BD213",
			"hello,                                       0x00000000, 0x248BFA47",
			"'Hello, world!',                             0x9747B28C, 0x24884CBA",
			"The quick brown fox jumps over the lazy dog, 0x9747B28C, 0x2FA826CD"
	})
	void murmur3MatchesPublishedTestVectors(String input, String seed, String expected) {
		int actual = Murmur3.hash32(input.getBytes(StandardCharsets.UTF_8), Integer.parseUnsignedInt(seed.substring(2), 16));

		assertThat(actual).isEqualTo(Integer.parseUnsignedInt(expected.substring(2), 16));
	}

	@Test
	void bucketsMatchIndependentlyComputedValues() {
		// E000042 hashes to a negative int; floorMod keeps the bucket non-negative.
		assertThat(assigner.bucketOf(new EmployeeId("E000042"))).isEqualTo(40);
		assertThat(assigner.bucketOf(new EmployeeId("E000001"))).isEqualTo(27);
		assertThat(assigner.bucketOf(new EmployeeId("E199999"))).isEqualTo(34);
	}

	@Test
	void sameIdAlwaysLandsInTheSameBucket() {
		var id = new EmployeeId("E123456");

		assertThat(assigner.bucketOf(id)).isEqualTo(new BucketAssigner(64).bucketOf(new EmployeeId("E123456")));
	}

	@Test
	void twoHundredThousandIdsSpreadEvenlyAcrossBuckets() {
		int[] counts = new int[assigner.bucketCount()];
		for (int i = 1; i <= 200_000; i++) {
			int bucket = assigner.bucketOf(new EmployeeId(String.format("E%06d", i)));
			assertThat(bucket).isBetween(0, assigner.bucketCount() - 1);
			counts[bucket]++;
		}

		int expected = 200_000 / assigner.bucketCount();
		for (int count : counts) {
			assertThat(count).isBetween((int) (expected * 0.9), (int) (expected * 1.1));
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, 32768})
	void bucketCountMustFitTheStoredColumn(int count) {
		assertThatThrownBy(() -> new BucketAssigner(count)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void singleBucketPutsEveryoneInBucketZero() {
		assertThat(new BucketAssigner(1).bucketOf(new EmployeeId("E000042"))).isZero();
	}
}
