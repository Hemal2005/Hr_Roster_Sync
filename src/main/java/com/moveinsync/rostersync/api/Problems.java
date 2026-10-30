package com.moveinsync.rostersync.api;

import com.moveinsync.rostersync.domain.ErrorCode;
import java.net.URI;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds every error body the API returns (RFC 7807): a fixed title from the status, a
 * stable {@code code} clients can branch on and the request's {@code traceId}. Nothing
 * from an exception message is ever copied in, because messages can carry record values.
 */
public final class Problems {

	private Problems() {
	}

	public static ProblemDetail of(HttpStatus status, ErrorCode code) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create("urn:rostersync:error:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code.name());
		problem.setProperty("traceId", MDC.get(TraceIdFilter.MDC_KEY));
		return problem;
	}

	public static HttpStatus statusOf(ErrorCode code) {
		return switch (code) {
			case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
			case FORBIDDEN -> HttpStatus.FORBIDDEN;
			case NOT_FOUND, UNKNOWN_EMPLOYEE -> HttpStatus.NOT_FOUND;
			case VERSION_CONFLICT, EMPLOYEE_INACTIVE, TRIP_DEPARTED, TRIP_FULL, ALREADY_BOOKED -> HttpStatus.CONFLICT;
			case TRANSPORT_VERSION_MISMATCH -> HttpStatus.PRECONDITION_FAILED;
			case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
			case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
			case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
			case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
			default -> HttpStatus.BAD_REQUEST;
		};
	}
}
