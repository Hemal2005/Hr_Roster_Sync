package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeField;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and transport-admin edits of employees, always inside one tenant. An employee of
 * another tenant is reported exactly like one that does not exist.
 */
@Service
public class EmployeeService {

	private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

	private final EmployeeRepository employees;
	private final TransactionTemplate tx;

	EmployeeService(EmployeeRepository employees, TransactionTemplate tx) {
		this.employees = employees;
		this.tx = tx;
	}

	public Employee get(TenantId tenantId, EmployeeId employeeId) {
		return employees.find(tenantId, employeeId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
	}

	/**
	 * Applies a transport-admin edit if {@code expectedVersion} is still the stored
	 * transport version (the client's If-Match). HR-owned fields and the HR version are
	 * untouched.
	 */
	public Employee updateTransport(TenantId tenantId, EmployeeId employeeId, TransportChanges changes,
			long expectedVersion) {
		return tx.execute(status -> {
			Employee employee = employees.findForUpdate(tenantId, employeeId)
					.orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
			Set<EmployeeField> changed = employee.applyTransport(changes.applyTo(employee.transportFields()),
					expectedVersion);
			if (!employees.updateTransportFields(tenantId, employee, expectedVersion)) {
				throw new DomainException(ErrorCode.TRANSPORT_VERSION_MISMATCH);
			}
			log.info("transport fields {} updated employee={} transportVersion={}", changed, employeeId,
					employee.transportVersion());
			return employee;
		});
	}
}
