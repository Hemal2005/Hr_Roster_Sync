package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.HrOwnedFields;
import com.moveinsync.rostersync.domain.PayloadHash;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.TransportOwnedFields;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * Read-only JPA view of an employee row. Writes never go through JPA: dirty checking
 * would write every mapped column, including the other owner's fields. They go through
 * {@link JdbcEmployeeRepository}'s owner-specific SQL instead.
 */
@Entity
@Immutable
@Table(name = "employee")
@IdClass(EmployeeKey.class)
class EmployeeEntity {

	@Id
	@Column(name = "tenant_id")
	private String tenantId;

	@Id
	@Column(name = "employee_id")
	private String employeeId;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	private String email;

	private String phone;

	@Column(name = "manager_id")
	private String managerId;

	@Column(name = "office_code")
	private String officeCode;

	@Column(name = "shift_code")
	private String shiftCode;

	private String status;

	@Column(name = "hr_version")
	private long hrVersion;

	@Column(name = "hr_payload_hash")
	private byte[] hrPayloadHash;

	@Column(name = "last_hr_event_at")
	private Instant lastHrEventAt;

	@Column(name = "pickup_point")
	private String pickupPoint;

	@Column(name = "special_assistance")
	private String specialAssistance;

	@Column(name = "transport_version")
	private long transportVersion;

	protected EmployeeEntity() {
	}

	Employee toDomain() {
		return Employee.restore(
				new TenantId(tenantId),
				new EmployeeId(employeeId),
				new HrOwnedFields(firstName, lastName, email, phone, managerId, officeCode, shiftCode),
				EmployeeStatus.valueOf(status),
				hrVersion,
				new PayloadHash(hrPayloadHash),
				lastHrEventAt,
				new TransportOwnedFields(pickupPoint, specialAssistance),
				transportVersion);
	}
}
