package com.moveinsync.rostersync.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

	private final TraceIdFilter filter = new TraceIdFilter();

	private String traceIdSeenDownstream(MockHttpServletRequest request, MockHttpServletResponse response)
			throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIdFilter.MDC_KEY)));
		return seen.get();
	}

	@Test
	void generatesATraceIdWhenTheCallerSendsNone() throws Exception {
		var response = new MockHttpServletResponse();

		String traceId = traceIdSeenDownstream(new MockHttpServletRequest(), response);

		assertThat(traceId).matches("[0-9a-f-]{36}");
		assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(traceId);
	}

	@Test
	void reusesAWellFormedIncomingTraceId() throws Exception {
		var request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.HEADER, "upstream-trace-1234");

		assertThat(traceIdSeenDownstream(request, new MockHttpServletResponse())).isEqualTo("upstream-trace-1234");
	}

	@Test
	void replacesAnIncomingTraceIdThatCouldForgeLogLines() throws Exception {
		var request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.HEADER, "abc\n{\"level\":\"ERROR\"}");

		assertThat(traceIdSeenDownstream(request, new MockHttpServletResponse()))
				.doesNotContain("\n")
				.matches("[0-9a-f-]{36}");
	}

	@Test
	void clearsTheLoggingContextAfterTheRequest() throws Exception {
		traceIdSeenDownstream(new MockHttpServletRequest(), new MockHttpServletResponse());

		assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
	}
}
