package com.moveinsync.rostersync.api.employee;

import com.moveinsync.rostersync.api.FieldsRejectedException;
import com.moveinsync.rostersync.application.EmployeeService;
import com.moveinsync.rostersync.application.TransportChanges;
import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeField;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.FieldOwner;
import com.moveinsync.rostersync.domain.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.OwnershipCheck;
import com.moveinsync.rostersync.domain.TenantId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Employee records within the caller's tenant. The ETag is the transport version, which
 * a transport edit must send back in If-Match.
 */
@RestController
@RequestMapping("/api/v1/employees")
class EmployeeController {

	private static final int MAX_TEXT = 500;

	private final EmployeeService employees;
	private final FieldOwnershipPolicy ownership = new FieldOwnershipPolicy();

	EmployeeController(EmployeeService employees) {
		this.employees = employees;
	}

	/** An employee's own record; the id comes from the token's subject, never the request. */
	@GetMapping("/me")
	ResponseEntity<EmployeeView> me(TenantId tenantId, @AuthenticationPrincipal Jwt token) {
		if (token.getSubject() == null || token.getSubject().isBlank()) {
			throw new DomainException(ErrorCode.NOT_FOUND);
		}
		return withETag(employees.get(tenantId, new EmployeeId(token.getSubject())));
	}

	@GetMapping("/{employeeId}")
	ResponseEntity<EmployeeView> get(TenantId tenantId, @PathVariable String employeeId) {
		return withETag(employees.get(tenantId, new EmployeeId(employeeId)));
	}

	/**
	 * Transport-admin edit of pickup point and special assistance. Requires If-Match with
	 * the current ETag, so two admins cannot silently overwrite each other; only
	 * transport-owned fields may be sent.
	 */
	@PatchMapping(path = "/{employeeId}/transport", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<EmployeeView> patchTransport(TenantId tenantId, @PathVariable String employeeId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody JsonNode body) {
		long expectedVersion = parseIfMatch(ifMatch);
		Employee updated = employees.updateTransport(tenantId, new EmployeeId(employeeId), changesFrom(body),
				expectedVersion);
		return withETag(updated);
	}

	private static long parseIfMatch(String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new DomainException(ErrorCode.PRECONDITION_REQUIRED);
		}
		String tag = ifMatch.strip();
		if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
			tag = tag.substring(1, tag.length() - 1);
		}
		try {
			long version = Long.parseLong(tag);
			if (version < 0) {
				throw new NumberFormatException();
			}
			return version;
		} catch (NumberFormatException e) {
			throw new FieldsRejectedException(ErrorCode.INVALID_REQUEST, List.of(HttpHeaders.IF_MATCH));
		}
	}

	private TransportChanges changesFrom(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new DomainException(ErrorCode.MALFORMED_REQUEST);
		}
		Set<String> names = new HashSet<>(body.propertyNames());
		OwnershipCheck check = ownership.check(FieldOwner.TRANSPORT, names);
		if (check.hasViolations()) {
			throw new FieldsRejectedException(ErrorCode.FIELD_NOT_OWNED,
					check.foreign().stream().map(EmployeeField::wireName).toList());
		}
		if (!check.unknown().isEmpty()) {
			throw new FieldsRejectedException(ErrorCode.INVALID_REQUEST, List.copyOf(check.unknown()));
		}
		String pickup = EmployeeField.PICKUP_POINT.wireName();
		String assistance = EmployeeField.SPECIAL_ASSISTANCE.wireName();
		return new TransportChanges(body.has(pickup), textOrNull(body, pickup), body.has(assistance),
				textOrNull(body, assistance));
	}

	private static String textOrNull(JsonNode body, String field) {
		JsonNode value = body.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isString() || value.stringValue().length() > MAX_TEXT) {
			throw new FieldsRejectedException(ErrorCode.INVALID_REQUEST, List.of(field));
		}
		return value.stringValue();
	}

	static ResponseEntity<EmployeeView> withETag(Employee employee) {
		return ResponseEntity.ok()
				.eTag(Long.toString(employee.transportVersion()))
				.body(EmployeeView.of(employee));
	}
}
