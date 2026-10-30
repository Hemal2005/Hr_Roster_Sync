package com.moveinsync.rostersync.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.application.reconciliation.ExportReader;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportGeneratorTest {

	@TempDir
	Path dir;

	private record Read(List<String> ids, List<ErrorCode> errors, ExportReader.Ending ending) {
	}

	private static Read read(Path file) {
		List<String> ids = new ArrayList<>();
		List<ErrorCode> errors = new ArrayList<>();
		ExportReader.Ending ending = ExportReader.read(file, new ExportReader.Handler() {
			@Override
			public void row(long lineNo, IncomingHrRecord record) {
				ids.add(record.employeeId());
			}

			@Override
			public void malformed(long lineNo, String employeeId, ErrorCode code) {
				errors.add(code);
			}
		});
		return new Read(ids, errors, ending);
	}

	@Test
	void fullFileReadsBackWithAMatchingTrailer() {
		Path file = ExportGenerator.write(dir.resolve("full.csv"), "acme", ExportGenerator.DEFAULT_AS_OF, 1000, null,
				ExportGenerator.DEFAULT_OFFICES);

		Read read = read(file);

		assertThat(read.ids()).hasSize(1000).startsWith("E000001").endsWith("E001000").doesNotHaveDuplicates();
		assertThat(read.errors()).isEmpty();
		assertThat(read.ending().trailerMatches()).isTrue();
		assertThat(ExportReader.readHeader(file).tenantId()).isEqualTo("acme");
	}

	@Test
	void truncatedFileEndsMidRowWithoutATrailer() throws Exception {
		Path file = ExportGenerator.write(dir.resolve("cut.csv"), "acme", ExportGenerator.DEFAULT_AS_OF, 1000, 600,
				ExportGenerator.DEFAULT_OFFICES);

		Read read = read(file);

		assertThat(read.ids()).hasSize(600);
		assertThat(read.errors()).containsExactly(ErrorCode.MALFORMED_RECORD);
		assertThat(read.ending().declaredCount()).isNull();
		assertThat(read.ending().trailerMatches()).isFalse();
		assertThat(Files.readString(file)).doesNotEndWith("\n");
	}

	@Test
	void sameArgumentsWriteTheSameBytes() throws Exception {
		Path a = ExportGenerator.write(dir.resolve("a.csv"), "acme", ExportGenerator.DEFAULT_AS_OF, 500, null,
				ExportGenerator.DEFAULT_OFFICES);
		Path b = ExportGenerator.write(dir.resolve("b.csv"), "acme", ExportGenerator.DEFAULT_AS_OF, 500, null,
				ExportGenerator.DEFAULT_OFFICES);

		assertThat(Files.mismatch(a, b)).isEqualTo(-1);
	}
}
