package com.moveinsync.rostersync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.support.IntegrationTest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT extends IntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(StructuredLoggingIT.class);

	@Test
	void logLinesAreJsonWithTheLoggingContextAsFields(CapturedOutput output) {
		MDC.put("traceId", "trace-for-logging-test");
		MDC.put("employeeId", "E000042");
		try {
			log.info("structured logging check");
		} finally {
			MDC.clear();
		}

		String line = Arrays.stream(output.getOut().split("\n"))
				.filter(l -> l.contains("structured logging check"))
				.findFirst()
				.orElseThrow();
		assertThat(line).startsWith("{").endsWith("}");
		assertThat(line).contains("\"traceId\":\"trace-for-logging-test\"", "\"employeeId\":\"E000042\"");
	}
}
