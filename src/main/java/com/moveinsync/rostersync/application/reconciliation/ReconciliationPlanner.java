package com.moveinsync.rostersync.application.reconciliation;

import com.moveinsync.rostersync.application.ReconciliationSource;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore.NewItem;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore.RunError;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.PayloadHasher;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import com.moveinsync.rostersync.domain.reconciliation.BucketPlan;
import com.moveinsync.rostersync.domain.reconciliation.FileEntry;
import com.moveinsync.rostersync.domain.reconciliation.PlanItem;
import com.moveinsync.rostersync.domain.reconciliation.ReconciliationAction;
import com.moveinsync.rostersync.domain.reconciliation.ReconciliationDiffer;
import com.moveinsync.rostersync.domain.validation.CompositeValidator;
import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.domain.validation.OfficeDirectory;
import com.moveinsync.rostersync.domain.validation.ValidationError;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds a reconciliation plan in two passes, holding at most one bucket in memory.
 *
 * <ol>
 * <li>Stream the export once: validate each row, hash its HR state, and append a compact
 * entry to the temp file of its bucket (floorMod(murmur3(id), N)). Invalid rows are
 * recorded by line and code, and still written as "present".</li>
 * <li>For each bucket, load its entries into a map, read the same bucket of the store
 * and diff them. Only the changes are stored.</li>
 * </ol>
 *
 * Time O(n + m), memory O((n + m) / N), disk O(n) for the temp files, which are deleted
 * when planning ends.
 */
@Component
public class ReconciliationPlanner {

	public static final String SOURCE_SYSTEM = ReconciliationSource.NAME;

	private static final int BATCH = 1000;
	private static final String VALID = "1";
	private static final String INVALID = "0";

	private final ReconciliationStore store;
	private final EmployeeRepository employees;
	private final OfficeDirectory offices;
	private final BucketAssigner buckets;
	private final ObjectMapper json;
	private final PayloadHasher hasher = new PayloadHasher();
	private final ReconciliationDiffer differ = new ReconciliationDiffer();

	ReconciliationPlanner(ReconciliationStore store, EmployeeRepository employees, OfficeDirectory offices,
			BucketAssigner buckets, ObjectMapper json) {
		this.store = store;
		this.employees = employees;
		this.offices = offices;
		this.buckets = buckets;
		this.json = json;
	}

	public PlanSummary plan(UUID runId, TenantId tenantId, Instant asOf, Path export) {
		long activeBefore = employees.countActive(tenantId);
		Path work = createWorkDirectory(runId);
		ErrorBuffer errors = new ErrorBuffer(runId);
		try {
			SplitResult split = splitIntoBuckets(tenantId, export, work, errors);
			long[] counts = diffBuckets(runId, tenantId, asOf, work, errors);
			errors.flush();
			ExportReader.Ending ending = split.ending();
			return new PlanSummary(ending.dataRows(), split.invalid() + counts[4], counts[0], counts[1], counts[2],
					counts[3], activeBefore, ending.declaredCount(), ending.trailerMatches());
		} finally {
			deleteRecursively(work);
		}
	}

	private record SplitResult(ExportReader.Ending ending, long invalid) {
	}

	private SplitResult splitIntoBuckets(TenantId tenantId, Path export, Path work, ErrorBuffer errors) {
		Map<String, Boolean> officeMemo = new HashMap<>();
		OfficeDirectory memoised = (tenant, code) ->
				officeMemo.computeIfAbsent(code, c -> offices.isKnownOffice(tenant, c));
		CompositeValidator validator = CompositeValidator.standard(memoised);
		CSVPrinter[] writers = new CSVPrinter[buckets.bucketCount()];
		long[] invalid = {0};
		try {
			ExportReader.Ending ending = ExportReader.read(export, new ExportReader.Handler() {
				@Override
				public void row(long lineNo, IncomingHrRecord record) {
					List<ValidationError> problems = validator.validate(tenantId, record);
					if (!problems.isEmpty()) {
						malformed(lineNo, record.employeeId(), problems.getFirst().code());
						return;
					}
					String hash = hasher.hash(record.toEvent(tenantId, SOURCE_SYSTEM)).hex();
					EmployeeStatus status = record.type() == HrEventType.DEACTIVATE
							? EmployeeStatus.INACTIVE
							: EmployeeStatus.ACTIVE;
					write(writers, work, record.employeeId(), record.employeeId(), Long.toString(record.version()),
							hash, status.name(), VALID, Long.toString(lineNo), record.firstName(), record.lastName(),
							record.email(), record.phone(), record.managerId(), record.officeCode(),
							record.shiftCode());
				}

				@Override
				public void malformed(long lineNo, String employeeId, ErrorCode code) {
					invalid[0]++;
					// A line with the wrong number of columns may not start with an id at all
					// (a shifted or garbled row can start with a name), so its first field is
					// never reported. It still marks that id present below.
					errors.add(new RunError(lineNo, code == ErrorCode.MALFORMED_RECORD ? null : employeeId, code));
					if (employeeId != null) {
						// Still present in the file: this employee must not be deactivated.
						write(writers, work, employeeId, employeeId, "0", "", "", INVALID, Long.toString(lineNo));
					}
				}
			});
			return new SplitResult(ending, invalid[0]);
		} finally {
			for (CSVPrinter writer : writers) {
				closeQuietly(writer);
			}
		}
	}

	/** Returns counts: create, update, deactivate, conflicts, duplicate rows. */
	private long[] diffBuckets(UUID runId, TenantId tenantId, Instant asOf, Path work, ErrorBuffer errors) {
		long[] counts = new long[5];
		for (int bucket = 0; bucket < buckets.bucketCount(); bucket++) {
			Map<String, CSVRecord> rows = readBucket(work, bucket, errors, counts);
			Map<String, FileEntry> file = new HashMap<>(rows.size() * 2);
			rows.forEach((id, row) -> file.put(id, toFileEntry(row)));

			BucketPlan plan = differ.diff(file, store.storedEntries(tenantId, bucket), asOf);

			List<NewItem> items = new ArrayList<>(plan.items().size());
			for (PlanItem item : plan.items()) {
				String payload = item.action() == ReconciliationAction.DEACTIVATE ? null : payload(rows.get(item.employeeId()));
				items.add(new NewItem(item, payload));
				if (items.size() == BATCH) {
					store.addItems(runId, items);
					items.clear();
				}
			}
			store.addItems(runId, items);
			for (String conflict : plan.conflicts()) {
				errors.add(new RunError(Long.parseLong(rows.get(conflict).get(5)), conflict, ErrorCode.VERSION_CONFLICT));
			}
			counts[0] += plan.count(ReconciliationAction.CREATE);
			counts[1] += plan.count(ReconciliationAction.UPDATE);
			counts[2] += plan.count(ReconciliationAction.DEACTIVATE);
			counts[3] += plan.conflicts().size();
		}
		return counts;
	}

	private Map<String, CSVRecord> readBucket(Path work, int bucket, ErrorBuffer errors, long[] counts) {
		Path file = bucketFile(work, bucket);
		Map<String, CSVRecord> rows = new LinkedHashMap<>();
		if (!Files.exists(file)) {
			return rows;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
				CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT)) {
			for (CSVRecord row : parser) {
				CSVRecord first = rows.putIfAbsent(row.get(0), row);
				if (first != null) {
					counts[4]++;
					errors.add(new RunError(Long.parseLong(row.get(5)), row.get(0), ErrorCode.DUPLICATE_IN_EXPORT));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return rows;
	}

	private static FileEntry toFileEntry(CSVRecord row) {
		if (!VALID.equals(row.get(4))) {
			return FileEntry.invalid(row.get(0));
		}
		return new FileEntry(row.get(0), Long.parseLong(row.get(1)), row.get(2), EmployeeStatus.valueOf(row.get(3)),
				true);
	}

	private String payload(CSVRecord row) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("employeeId", row.get(0));
		payload.put("version", Long.parseLong(row.get(1)));
		payload.put("status", row.get(3));
		payload.put("firstName", emptyToNull(row.get(6)));
		payload.put("lastName", emptyToNull(row.get(7)));
		payload.put("email", emptyToNull(row.get(8)));
		payload.put("phone", emptyToNull(row.get(9)));
		payload.put("managerId", emptyToNull(row.get(10)));
		payload.put("officeCode", emptyToNull(row.get(11)));
		payload.put("shiftCode", emptyToNull(row.get(12)));
		return json.writeValueAsString(payload);
	}

	private void write(CSVPrinter[] writers, Path work, String employeeId, Object... values) {
		int bucket = buckets.bucketOf(new EmployeeId(employeeId));
		try {
			if (writers[bucket] == null) {
				BufferedWriter out = Files.newBufferedWriter(bucketFile(work, bucket), StandardCharsets.UTF_8);
				writers[bucket] = new CSVPrinter(out, CSVFormat.DEFAULT);
			}
			writers[bucket].printRecord(values);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path bucketFile(Path work, int bucket) {
		return work.resolve("bucket-" + bucket + ".csv");
	}

	private static Path createWorkDirectory(UUID runId) {
		try {
			return Files.createTempDirectory("reconciliation-" + runId);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void deleteRecursively(Path dir) {
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void closeQuietly(CSVPrinter writer) {
		if (writer == null) {
			return;
		}
		try {
			writer.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	/** Collects per-line errors and writes them in batches. */
	private final class ErrorBuffer {

		private final UUID runId;
		private final List<RunError> pending = new ArrayList<>();

		ErrorBuffer(UUID runId) {
			this.runId = runId;
		}

		void add(RunError error) {
			pending.add(error);
			if (pending.size() == BATCH) {
				flush();
			}
		}

		void flush() {
			store.addErrors(runId, pending);
			pending.clear();
		}
	}
}
