package com.moveinsync.rostersync.application.reconciliation;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Streams the nightly export one row at a time; nothing but the current row is held.
 *
 * <pre>
 * #HEADER,tenant=acme,as_of=2026-10-30T02:00:00Z
 * employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status
 * E000001,12,...
 * #TRAILER,count=200000
 * </pre>
 *
 * A file that stops early has no trailer (or a wrong count), and its last line is often
 * cut mid-row; such a line is reported as malformed, never silently dropped.
 */
public final class ExportReader {

	public static final List<String> COLUMNS = List.of("employee_id", "version", "first_name", "last_name", "email",
			"phone", "manager_id", "office_code", "shift_code", "status");

	public record Header(String tenantId, Instant asOf) {
	}

	public interface Handler {

		void row(long lineNo, IncomingHrRecord record);

		/** A row that could not be read as a record; {@code employeeId} is null if unknown. */
		void malformed(long lineNo, String employeeId, ErrorCode code);
	}

	/** What the end of the file looked like. */
	public record Ending(long dataRows, Long declaredCount, boolean rowsAfterTrailer) {

		public boolean trailerMatches() {
			return declaredCount != null && declaredCount == dataRows && !rowsAfterTrailer;
		}
	}

	private ExportReader() {
	}

	public static Header readHeader(Path file) {
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return parseHeader(reader.readLine());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static Ending read(Path file, Handler handler) {
		long dataRows = 0;
		Long declared = null;
		boolean rowsAfterTrailer = false;
		long lineNo = 2;
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Instant asOf = parseHeader(reader.readLine()).asOf();
			CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
			try (CSVParser parser = CSVParser.parse(reader, format)) {
				if (!parser.getHeaderNames().equals(COLUMNS)) {
					throw new DomainException(ErrorCode.INVALID_EXPORT_HEADER);
				}
				for (CSVRecord csv : parser) {
					lineNo++;
					String first = csv.get(0);
					if (first.startsWith("#TRAILER")) {
						declared = parseTrailer(csv);
						continue;
					}
					if (declared != null) {
						rowsAfterTrailer = true;
					}
					dataRows++;
					String employeeId = blankToNull(first);
					if (!csv.isConsistent()) {
						handler.malformed(lineNo, employeeId, ErrorCode.MALFORMED_RECORD);
						continue;
					}
					try {
						handler.row(lineNo, toRecord(csv, asOf));
					} catch (NumberFormatException e) {
						handler.malformed(lineNo, employeeId, ErrorCode.INVALID_VERSION);
					} catch (IllegalArgumentException e) {
						handler.malformed(lineNo, employeeId, ErrorCode.INVALID_EVENT_TYPE);
					}
				}
			} catch (UncheckedIOException e) {
				// A row the CSV parser cannot finish (for example an unclosed quote at a cut-off
				// end) ends the file here; without a trailer the run is treated as truncated.
				dataRows++;
				handler.malformed(lineNo + 1, null, ErrorCode.MALFORMED_RECORD);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new Ending(dataRows, declared, rowsAfterTrailer);
	}

	static Header parseHeader(String line) {
		if (line == null || !line.startsWith("#HEADER")) {
			throw new DomainException(ErrorCode.INVALID_EXPORT_HEADER);
		}
		Map<String, String> values = new HashMap<>();
		Arrays.stream(line.split(",")).skip(1).forEach(part -> {
			int eq = part.indexOf('=');
			if (eq > 0) {
				values.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
			}
		});
		String tenant = values.get("tenant");
		String asOf = values.get("as_of");
		if (tenant == null || tenant.isBlank() || asOf == null) {
			throw new DomainException(ErrorCode.INVALID_EXPORT_HEADER);
		}
		try {
			return new Header(tenant, Instant.parse(asOf));
		} catch (DateTimeParseException e) {
			throw new DomainException(ErrorCode.INVALID_EXPORT_HEADER);
		}
	}

	private static Long parseTrailer(CSVRecord csv) {
		for (String value : csv) {
			if (value.startsWith("count=")) {
				try {
					return Long.parseLong(value.substring("count=".length()).trim());
				} catch (NumberFormatException e) {
					return -1L;
				}
			}
		}
		return -1L;
	}

	private static IncomingHrRecord toRecord(CSVRecord csv, Instant asOf) {
		HrEventType type = switch (csv.get("status").trim()) {
			case "ACTIVE" -> HrEventType.UPDATE;
			case "INACTIVE" -> HrEventType.DEACTIVATE;
			default -> throw new IllegalArgumentException("status");
		};
		return new IncomingHrRecord(type, blankToNull(csv.get("employee_id")), Long.parseLong(csv.get("version").trim()),
				asOf, blankToNull(csv.get("first_name")), blankToNull(csv.get("last_name")),
				blankToNull(csv.get("email")), blankToNull(csv.get("phone")), blankToNull(csv.get("manager_id")),
				blankToNull(csv.get("office_code")), blankToNull(csv.get("shift_code")));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
