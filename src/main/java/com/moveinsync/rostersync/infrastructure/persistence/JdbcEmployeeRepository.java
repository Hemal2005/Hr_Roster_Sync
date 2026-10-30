package com.moveinsync.rostersync.infrastructure.persistence;

import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.HrOwnedFields;
import com.moveinsync.rostersync.domain.PayloadHash;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.TransportOwnedFields;
import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain reads go through JPA. The locking read and all writes are hand-written SQL so
 * that each write path names only the columns its owner may change and checks its
 * version in the same statement.
 */
@Repository
class JdbcEmployeeRepository implements EmployeeRepository {

	private static final String SELECT_COLUMNS = """
			SELECT tenant_id, employee_id, first_name, last_name, email, phone, manager_id, office_code,
			       shift_code, status, hr_version, hr_payload_hash, last_hr_event_at,
			       pickup_point, special_assistance, transport_version
			FROM employee
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final EmployeeJpaRepository jpa;
	private final BucketAssigner buckets;

	JdbcEmployeeRepository(NamedParameterJdbcTemplate jdbc, EmployeeJpaRepository jpa, BucketAssigner buckets) {
		this.jdbc = jdbc;
		this.jpa = jpa;
		this.buckets = buckets;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Employee> find(TenantId tenantId, EmployeeId employeeId) {
		return jpa.findByTenantIdAndEmployeeId(tenantId.value(), employeeId.value()).map(EmployeeEntity::toDomain);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Employee> findForUpdate(TenantId tenantId, EmployeeId employeeId) {
		return jdbc.query(SELECT_COLUMNS + "WHERE tenant_id = :tenantId AND employee_id = :employeeId FOR UPDATE",
				key(tenantId, employeeId), (rs, row) -> toDomain(rs)).stream().findFirst();
	}

	@Override
	public void insert(TenantId tenantId, Employee employee) {
		requireSameTenant(tenantId, employee);
		if (employee.transportVersion() != 0) {
			throw new IllegalArgumentException("a new employee has no transport data yet");
		}
		jdbc.update("""
				INSERT INTO employee (tenant_id, employee_id, first_name, last_name, email, phone, manager_id,
				                      office_code, shift_code, status, hr_version, hr_payload_hash,
				                      last_hr_event_at, bucket)
				VALUES (:tenantId, :employeeId, :firstName, :lastName, :email, :phone, :managerId,
				        :officeCode, :shiftCode, :status, :hrVersion, :hrPayloadHash,
				        :lastHrEventAt, :bucket)
				""", hrColumns(employee).addValue("bucket", buckets.bucketOf(employee.employeeId())));
	}

	@Override
	public boolean updateHrFields(TenantId tenantId, Employee employee) {
		requireSameTenant(tenantId, employee);
		int rows = jdbc.update("""
				UPDATE employee
				SET first_name = :firstName, last_name = :lastName, email = :email, phone = :phone,
				    manager_id = :managerId, office_code = :officeCode, shift_code = :shiftCode,
				    status = :status, hr_version = :hrVersion, hr_payload_hash = :hrPayloadHash,
				    last_hr_event_at = :lastHrEventAt, updated_at = now()
				WHERE tenant_id = :tenantId AND employee_id = :employeeId AND hr_version < :hrVersion
				""", hrColumns(employee));
		return rows == 1;
	}

	@Override
	public boolean updateTransportFields(TenantId tenantId, Employee employee, long expectedVersion) {
		requireSameTenant(tenantId, employee);
		if (employee.transportVersion() != expectedVersion + 1) {
			throw new IllegalArgumentException("employee was not advanced from the expected transport version");
		}
		TransportOwnedFields transport = employee.transportFields();
		int rows = jdbc.update("""
				UPDATE employee
				SET pickup_point = :pickupPoint, special_assistance = :specialAssistance,
				    transport_version = :transportVersion, updated_at = now()
				WHERE tenant_id = :tenantId AND employee_id = :employeeId AND transport_version = :expectedVersion
				""", key(employee.tenantId(), employee.employeeId())
				.addValue("pickupPoint", transport.pickupPoint())
				.addValue("specialAssistance", transport.specialAssistance())
				.addValue("transportVersion", employee.transportVersion())
				.addValue("expectedVersion", expectedVersion));
		return rows == 1;
	}

	@Override
	@Transactional(readOnly = true)
	public long countActive(TenantId tenantId) {
		return jpa.countByTenantIdAndStatus(tenantId.value(), EmployeeStatus.ACTIVE.name());
	}

	private static MapSqlParameterSource key(TenantId tenantId, EmployeeId employeeId) {
		return new MapSqlParameterSource()
				.addValue("tenantId", tenantId.value())
				.addValue("employeeId", employeeId.value());
	}

	private static MapSqlParameterSource hrColumns(Employee employee) {
		HrOwnedFields hr = employee.hrFields();
		return key(employee.tenantId(), employee.employeeId())
				.addValue("firstName", hr.firstName())
				.addValue("lastName", hr.lastName())
				.addValue("email", hr.email())
				.addValue("phone", hr.phone())
				.addValue("managerId", hr.managerId())
				.addValue("officeCode", hr.officeCode())
				.addValue("shiftCode", hr.shiftCode())
				.addValue("status", employee.status().name())
				.addValue("hrVersion", employee.hrVersion())
				.addValue("hrPayloadHash", employee.hrPayloadHash().bytes())
				.addValue("lastHrEventAt", OffsetDateTime.ofInstant(employee.lastHrEventAt(), ZoneOffset.UTC));
	}

	private static void requireSameTenant(TenantId tenantId, Employee employee) {
		if (!employee.tenantId().equals(tenantId)) {
			throw new IllegalArgumentException("employee belongs to a different tenant");
		}
	}

	private static Employee toDomain(ResultSet rs) throws SQLException {
		Instant lastHrEventAt = rs.getObject("last_hr_event_at", OffsetDateTime.class).toInstant();
		return Employee.restore(
				new TenantId(rs.getString("tenant_id")),
				new EmployeeId(rs.getString("employee_id")),
				new HrOwnedFields(rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
						rs.getString("phone"), rs.getString("manager_id"), rs.getString("office_code"),
						rs.getString("shift_code")),
				EmployeeStatus.valueOf(rs.getString("status")),
				rs.getLong("hr_version"),
				new PayloadHash(rs.getBytes("hr_payload_hash")),
				lastHrEventAt,
				new TransportOwnedFields(rs.getString("pickup_point"), rs.getString("special_assistance")),
				rs.getLong("transport_version"));
	}
}
