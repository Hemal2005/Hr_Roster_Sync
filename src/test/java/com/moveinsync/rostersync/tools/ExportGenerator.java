package com.moveinsync.rostersync.tools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a synthetic nightly export: the same rows every time for the same arguments, so
 * a test can seed the matching employees and expect an unchanged row to be a no-op.
 * Only the JDK is used, so it runs from {@code target/test-classes} without the app.
 *
 * <pre>
 * scripts/generate-export.sh --tenant acme --rows 200000 --out data/generated/full.csv
 * scripts/generate-export.sh --rows 200000 --truncate-at 120000 --out data/generated/cut.csv
 * </pre>
 *
 * With {@code --truncate-at N} the file holds N whole rows, then half of the next row and
 * no trailer, which is what an interrupted transfer looks like.
 */
public final class ExportGenerator {

	public static final String COLUMNS =
			"employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status";
	public static final Instant DEFAULT_AS_OF = Instant.parse("2026-10-30T02:00:00Z");
	public static final List<String> DEFAULT_OFFICES = List.of("BLR-01", "HYD-02");

	private static final String[] FIRST_NAMES = {"Asha", "Ravi", "Meera", "Arjun", "Kavya", "Vikram", "Nisha", "Rohan"};
	private static final String[] LAST_NAMES = {"Iyer", "Sharma", "Reddy", "Menon", "Nair", "Gupta", "Rao", "Das"};
	private static final String[] SHIFTS = {"DAY", "EVENING", "NIGHT"};

	private ExportGenerator() {
	}

	/** {@code E000001} for 1. */
	public static String employeeId(int n) {
		return "E%06d".formatted(n);
	}

	/** The row for employee {@code n} at {@code version}, in {@link #COLUMNS} order. */
	public static List<String> fields(String tenant, int n, long version, List<String> offices) {
		String id = employeeId(n);
		return List.of(
				id,
				Long.toString(version),
				FIRST_NAMES[n % FIRST_NAMES.length],
				LAST_NAMES[(n / FIRST_NAMES.length) % LAST_NAMES.length],
				id.toLowerCase() + "@" + tenant + ".example",
				"+9198" + "%08d".formatted(n),
				employeeId(n == 1 ? 2 : 1),
				offices.get(n % offices.size()),
				SHIFTS[n % SHIFTS.length],
				"ACTIVE");
	}

	/**
	 * Writes {@code rows} employees (E000001 upwards) at version 1. When {@code truncateAt}
	 * is not null and smaller than {@code rows}, the file is cut after that many rows.
	 */
	public static Path write(Path out, String tenant, Instant asOf, int rows, Integer truncateAt,
			List<String> offices) {
		int whole = truncateAt == null ? rows : Math.min(rows, truncateAt);
		boolean truncated = whole < rows;
		try {
			if (out.getParent() != null) {
				Files.createDirectories(out.getParent());
			}
			try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
				writer.write("#HEADER,tenant=" + tenant + ",as_of=" + asOf + "\n");
				writer.write(COLUMNS + "\n");
				for (int n = 1; n <= whole; n++) {
					writer.write(String.join(",", fields(tenant, n, 1, offices)));
					writer.write('\n');
				}
				if (truncated) {
					String next = String.join(",", fields(tenant, whole + 1, 1, offices));
					writer.write(next, 0, next.length() / 2);
				} else {
					writer.write("#TRAILER,count=" + rows + "\n");
				}
			}
			return out;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void main(String[] args) {
		Map<String, String> options = new HashMap<>();
		for (int i = 0; i < args.length; i++) {
			if (!args[i].startsWith("--") || i + 1 == args.length) {
				usage();
				return;
			}
			options.put(args[i].substring(2), args[++i]);
		}
		String tenant = options.getOrDefault("tenant", "acme");
		int rows = Integer.parseInt(options.getOrDefault("rows", "200000"));
		Integer truncateAt = options.containsKey("truncate-at") ? Integer.parseInt(options.get("truncate-at")) : null;
		Instant asOf = Instant.parse(options.getOrDefault("as-of", DEFAULT_AS_OF.toString()));
		List<String> offices = options.containsKey("offices") ? List.of(options.get("offices").split(","))
				: DEFAULT_OFFICES;
		Path out = Path.of(options.getOrDefault("out", "data/generated/export-" + tenant + "-" + rows + ".csv"));

		long started = System.nanoTime();
		write(out, tenant, asOf, rows, truncateAt, offices);
		System.out.printf("wrote %s (%d rows%s) in %d ms%n", out, truncateAt == null ? rows : Math.min(rows, truncateAt),
				truncateAt != null && truncateAt < rows ? ", truncated, no trailer" : "",
				(System.nanoTime() - started) / 1_000_000);
	}

	private static void usage() {
		System.err.println("usage: generate-export.sh [--tenant acme] [--rows 200000] [--truncate-at N] "
				+ "[--as-of 2026-10-30T02:00:00Z] [--offices BLR-01,HYD-02] [--out data/generated/x.csv]");
	}
}
