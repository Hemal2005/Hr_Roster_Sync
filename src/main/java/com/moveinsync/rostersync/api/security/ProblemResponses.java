package com.moveinsync.rostersync.api.security;

import com.moveinsync.rostersync.api.Problems;
import com.moveinsync.rostersync.domain.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 401 and 403 are decided in the security filter chain, before any controller, so they
 * need their own writers to produce the same problem body as every other error. The
 * standard bearer-token handlers still run first to set the WWW-Authenticate header.
 */
final class ProblemResponses {

	private ProblemResponses() {
	}

	static AuthenticationEntryPoint unauthenticated(ObjectMapper json) {
		BearerTokenAuthenticationEntryPoint standard = new BearerTokenAuthenticationEntryPoint();
		return (request, response, exception) -> {
			standard.commence(request, response, exception);
			write(json, response, ErrorCode.UNAUTHENTICATED);
		};
	}

	static AccessDeniedHandler forbidden(ObjectMapper json) {
		BearerTokenAccessDeniedHandler standard = new BearerTokenAccessDeniedHandler();
		return (request, response, exception) -> {
			standard.handle(request, response, exception);
			write(json, response, ErrorCode.FORBIDDEN);
		};
	}

	private static void write(ObjectMapper json, HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(Problems.statusOf(code).value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		json.writeValue(response.getOutputStream(), Problems.of(Problems.statusOf(code), code));
	}
}
