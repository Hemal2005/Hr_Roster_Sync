package com.moveinsync.rostersync.domain.reconciliation;

/**
 * MurmurHash3, x86 32-bit variant. Fast, well distributed, and not tied to Java's
 * {@code String.hashCode}, so the bucket of an employee id is stable across JVM
 * versions and reproducible in any language.
 */
final class Murmur3 {

	private static final int C1 = 0xcc9e2d51;
	private static final int C2 = 0x1b873593;

	private Murmur3() {
	}

	static int hash32(byte[] data, int seed) {
		int h1 = seed;
		int length = data.length;
		int blockEnd = length & ~3;

		for (int i = 0; i < blockEnd; i += 4) {
			int k1 = (data[i] & 0xff)
					| (data[i + 1] & 0xff) << 8
					| (data[i + 2] & 0xff) << 16
					| (data[i + 3] & 0xff) << 24;
			h1 ^= mixK1(k1);
			h1 = Integer.rotateLeft(h1, 13);
			h1 = h1 * 5 + 0xe6546b64;
		}

		int tail = length & 3;
		if (tail > 0) {
			int k1 = 0;
			if (tail == 3) {
				k1 ^= (data[blockEnd + 2] & 0xff) << 16;
			}
			if (tail >= 2) {
				k1 ^= (data[blockEnd + 1] & 0xff) << 8;
			}
			k1 ^= data[blockEnd] & 0xff;
			h1 ^= mixK1(k1);
		}

		h1 ^= length;
		return finalMix(h1);
	}

	private static int mixK1(int k1) {
		k1 *= C1;
		k1 = Integer.rotateLeft(k1, 15);
		return k1 * C2;
	}

	private static int finalMix(int h) {
		h ^= h >>> 16;
		h *= 0x85ebca6b;
		h ^= h >>> 13;
		h *= 0xc2b2ae35;
		h ^= h >>> 16;
		return h;
	}
}
