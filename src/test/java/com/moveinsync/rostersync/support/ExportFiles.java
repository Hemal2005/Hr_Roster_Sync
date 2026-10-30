package com.moveinsync.rostersync.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Small nightly export files for tests, with the same personal values as {@link TestRecords}
 * so that an unchanged row hashes the same as an employee created through the API.
 */
public final class ExportFiles {

	public static final String COLUMNS =
			"employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status";

	private ExportFiles() {
	}

	public static String row(String employeeId, long version, String officeCode, String shiftCode, String status) {
		return String.join(",", employeeId, Long.toString(version), "Priyanka", "Raghunathan",
				employeeId.toLowerCase() + "@acme.example", TestRecords.PHONE, "E000001", officeCode, shiftCode, status);
	}

	public static String active(String employeeId, long version, String officeCode, String shiftCode) {
		return row(employeeId, version, officeCode, shiftCode, "ACTIVE");
	}

	/** Writes header, rows and, if {@code withTrailer}, a trailer with the true row count. */
	public static Path write(Path dir, String tenant, Instant asOf, List<String> rows, boolean withTrailer) {
		List<String> lines = new ArrayList<>();
		lines.add("#HEADER,tenant=" + tenant + ",as_of=" + asOf);
		lines.add(COLUMNS);
		lines.addAll(rows);
		if (withTrailer) {
			lines.add("#TRAILER,count=" + rows.size());
		}
		return writeLines(dir, lines);
	}

	public static Path writeLines(Path dir, List<String> lines) {
		try {
			Path file = Files.createTempFile(dir, "export-", ".csv");
			Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
			return file;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
