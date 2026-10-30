package com.moveinsync.rostersync.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

	private static final String PHONE = "+919876543210";

	@RestController
	static class ThrowingController {

		@GetMapping("/domain")
		String domain() {
			throw new DomainException(ErrorCode.TRANSPORT_VERSION_MISMATCH);
		}

		@GetMapping("/boom")
		String boom() {
			throw new IllegalStateException("duplicate key (phone)=(" + PHONE + ")");
		}

		record Body(@NotBlank String name, @NotBlank String phone) {
		}

		@PostMapping("/body")
		String body(@Valid @RequestBody Body body) {
			return "ok";
		}
	}

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(new TraceIdFilter())
			.build();

	@Test
	void domainErrorMapsToItsStatusAndCode() throws Exception {
		mvc.perform(get("/domain"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("TRANSPORT_VERSION_MISMATCH"))
				.andExpect(jsonPath("$.status").value(412))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void traceIdInTheBodyMatchesTheResponseHeader() throws Exception {
		mvc.perform(get("/domain").header(TraceIdFilter.HEADER, "trace-abc-123"))
				.andExpect(header().string(TraceIdFilter.HEADER, "trace-abc-123"))
				.andExpect(jsonPath("$.traceId").value("trace-abc-123"));
	}

	@Test
	void unexpectedErrorIs500WithoutTheExceptionMessage() throws Exception {
		mvc.perform(get("/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(Matchers.not(Matchers.containsString(PHONE))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("duplicate key"))));
	}

	@Test
	void invalidBodyNamesTheFieldsButNotTheirValues() throws Exception {
		mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\",\"phone\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.fields", Matchers.contains("name", "phone")));
	}

	@Test
	void malformedJsonIsMalformedRequest() throws Exception {
		mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\": " + PHONE))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
				.andExpect(content().string(Matchers.not(Matchers.containsString(PHONE))));
	}

	@Test
	void wrongMethodIs405() throws Exception {
		mvc.perform(post("/domain"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void wrongContentTypeIs415() throws Exception {
		mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}
}
