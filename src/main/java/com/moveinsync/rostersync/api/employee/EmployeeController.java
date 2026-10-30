package com.moveinsync.rostersync.api.employee;

import com.moveinsync.rostersync.application.EmployeeService;
import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee records within the caller's tenant. The ETag is the transport version, which
 * a transport edit must send back in If-Match.
 */
@RestController
@RequestMapping("/api/v1/employees")
class EmployeeController {

	private final EmployeeService employees;

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

	static ResponseEntity<EmployeeView> withETag(Employee employee) {
		return ResponseEntity.ok()
				.eTag(Long.toString(employee.transportVersion()))
				.body(EmployeeView.of(employee));
	}
}
