package com.moveinsync.rostersync.application.reconciliation;

import static com.moveinsync.rostersync.support.ExportFiles.COLUMNS;
import static com.moveinsync.rostersync.support.ExportFiles.active;
import static com.moveinsync.rostersync.support.ExportFiles.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.validation.HrEventType;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import com.moveinsync.rostersync.support.ExportFiles;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportReaderTest {

	private static final Instant AS_OF = Instant.parse("2026-10-30T02:00:00Z");

	@TempDir
	Path dir;

	private final List<String> rows = new ArrayList<>();
	private final List<String> malformed = new ArrayList<>();
	private final List<IncomingHrRecord> records = new ArrayList<>();

	private final ExportReader.Handler collect = new ExportReader.Handler() {
		@Override
		public void row(long lineNo, IncomingHrRecord record) {
			rows.add(lineNo + ":" + record.employeeId());
			records.add(record);
		}

		@Override
		public void malformed(long lineNo, String employeeId, ErrorCode code) {
			malformed.add(lineNo + ":" + employeeId + ":" + code);
		}
	};

	@Test
	void readsTheHeader() {
		Path file = ExportFiles.write(dir, "acme", AS_OF, List.of(), true);

		assertThat(ExportReader.readHeader(file)).isEqualTo(new ExportReader.Header("acme", AS_OF));
	}

	@Test
	void streamsRowsWithFileLineNumbersAndChecksTheTrailer() {
		Path file = ExportFiles.write(dir, "acme", AS_OF,
				List.of(active("E1", 3, "BLR-01", "DAY"), row("E2", 4, "BLR-01", "DAY", "INACTIVE")), true);

		ExportReader.Ending ending = ExportReader.read(file, collect);

		assertThat(rows).containsExactly("3:E1", "4:E2");
		assertThat(records.get(0).type()).isEqualTo(HrEventType.UPDATE);
		assertThat(records.get(0).occurredAt()).isEqualTo(AS_OF);
		assertThat(records.get(1).type()).isEqualTo(HrEventType.DEACTIVATE);
		assertThat(ending.dataRows()).isEqualTo(2);
		assertThat(ending.trailerMatches()).isTrue();
	}

	@Test
	void missingTrailerMeansTheFileMayBeCutShort() {
		Path file = ExportFiles.write(dir, "acme", AS_OF, List.of(active("E1", 3, "BLR-01", "DAY")), false);

		ExportReader.Ending ending = ExportReader.read(file, collect);

		assertThat(ending.declaredCount()).isNull();
		assertThat(ending.trailerMatches()).isFalse();
	}

	@Test
	void trailerCountThatDisagreesFails() {
		Path file = ExportFiles.writeLines(dir, List.of("#HEADER,tenant=acme,as_of=" + AS_OF, COLUMNS,
				active("E1", 3, "BLR-01", "DAY"), "#TRAILER,count=2"));

		assertThat(ExportReader.read(file, collect).trailerMatches()).isFalse();
	}

	@Test
	void rowsAfterTheTrailerFail() {
		Path file = ExportFiles.writeLines(dir, List.of("#HEADER,tenant=acme,as_of=" + AS_OF, COLUMNS,
				active("E1", 3, "BLR-01", "DAY"), "#TRAILER,count=1", active("E2", 3, "BLR-01", "DAY")));

		ExportReader.Ending ending = ExportReader.read(file, collect);

		assertThat(ending.rowsAfterTrailer()).isTrue();
		assertThat(ending.trailerMatches()).isFalse();
	}

	@Test
	void lineCutMidRowIsMalformedButKeepsItsEmployeeId() {
		Path file = ExportFiles.writeLines(dir, List.of("#HEADER,tenant=acme,as_of=" + AS_OF, COLUMNS,
				active("E1", 3, "BLR-01", "DAY"), "E120001,5,Priy"));

		ExportReader.read(file, collect);

		assertThat(rows).containsExactly("3:E1");
		assertThat(malformed).containsExactly("4:E120001:MALFORMED_RECORD");
	}

	@Test
	void badVersionAndBadStatusAreMalformedRows() {
		Path file = ExportFiles.write(dir, "acme", AS_OF,
				List.of(active("E1", 3, "BLR-01", "DAY").replace(",3,", ",x,"), row("E2", 3, "BLR-01", "DAY", "ON_LEAVE")),
				true);

		ExportReader.read(file, collect);

		assertThat(malformed).containsExactly("3:E1:INVALID_VERSION", "4:E2:INVALID_EVENT_TYPE");
	}

	@Test
	void emptyOptionalFieldsBecomeNull() {
		Path file = ExportFiles.write(dir, "acme", AS_OF,
				List.of("E1,3,Priyanka,,p@acme.example,+919876543210,,BLR-01,DAY,ACTIVE"), true);

		ExportReader.read(file, collect);

		assertThat(records.getFirst().lastName()).isNull();
		assertThat(records.getFirst().managerId()).isNull();
	}

	@Test
	void missingOrBrokenHeaderIsRefused() {
		Path noMeta = ExportFiles.writeLines(dir, List.of(COLUMNS, active("E1", 3, "BLR-01", "DAY")));
		Path noAsOf = ExportFiles.writeLines(dir, List.of("#HEADER,tenant=acme", COLUMNS));
		Path badColumns = ExportFiles.writeLines(dir, List.of("#HEADER,tenant=acme,as_of=" + AS_OF, "id,version"));

		for (Path file : List.of(noMeta, noAsOf, badColumns)) {
			assertThatThrownBy(() -> ExportReader.read(file, collect))
					.isInstanceOf(DomainException.class)
					.extracting(e -> ((DomainException) e).code())
					.isEqualTo(ErrorCode.INVALID_EXPORT_HEADER);
		}
	}
}
