package com.moveinsync.rostersync.api;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception that leaves a controller into a ProblemDetail with a stable
 * code. Validation errors name the offending fields, never their values.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> domain(DomainException e) {
		return respond(Problems.statusOf(e.code()), e.code());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
		return respond(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
		ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
		List<String> fields = e.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField())
				.distinct()
				.sorted()
				.toList();
		problem.setProperty("fields", fields);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ProblemDetail> noResource(NoResourceFoundException e) {
		return respond(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException e) {
		return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e) {
		return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> unexpected(Exception e) {
		// Class and location only: the message of a database or library exception can
		// contain the values that caused it.
		StackTraceElement origin = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
		log.error("unhandled {} at {}", e.getClass().getName(), origin);
		return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
	}

	private static ResponseEntity<ProblemDetail> respond(HttpStatus status, ErrorCode code) {
		return ResponseEntity.status(status).body(Problems.of(status, code));
	}
}
