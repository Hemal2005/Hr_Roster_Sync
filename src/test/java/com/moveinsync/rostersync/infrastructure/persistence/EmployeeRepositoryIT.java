package com.moveinsync.rostersync.infrastructure.persistence;

import static com.moveinsync.rostersync.domain.TestEvents.EMPLOYEE;
import static com.moveinsync.rostersync.domain.TestEvents.T0;
import static com.moveinsync.rostersync.domain.TestEvents.TENANT;
import static com.moveinsync.rostersync.domain.TestEvents.create;
import static com.moveinsync.rostersync.domain.TestEvents.deactivate;
import static com.moveinsync.rostersync.domain.TestEvents.fields;
import static com.moveinsync.rostersync.domain.TestEvents.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.rostersync.domain.CreateEmployee;
import com.moveinsync.rostersync.domain.DeactivateEmployee;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeRepository;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.EventHeader;
import com.moveinsync.rostersync.domain.HrEvent;
import com.moveinsync.rostersync.domain.PayloadHasher;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.TransportOwnedFields;
import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import com.moveinsync.rostersync.support.IntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class EmployeeRepositoryIT extends IntegrationTest {

	private static final TenantId GLOBEX = new TenantId("globex");

	@Autowired
	EmployeeRepository employees;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	BucketAssigner buckets;

	private final PayloadHasher hasher = new PayloadHasher();

	@BeforeEach
	void tenants() {
		givenTenant(TENANT.value(), "BLR-01", "HYD-02");
		givenTenant(GLOBEX.value(), "BLR-01");
	}

	private Employee newEmployee(HrEvent event) {
		Employee employee = Employee.createFrom(event, hasher.hash(event));
		employees.insert(event.tenantId(), employee);
		return employee;
	}

	private Employee stored() {
		return employees.find(TENANT, EMPLOYEE).orElseThrow();
	}

	@Test
	void insertedEmployeeReadsBackWithEveryValue() {
		var event = create(1, "BLR-01", "MORNING");
		newEmployee(event);

		Employee loaded = stored();

		assertThat(loaded.hrFields()).isEqualTo(event.fields());
		assertThat(loaded.status()).isEqualTo(EmployeeStatus.ACTIVE);
		assertThat(loaded.hrVersion()).isEqualTo(1);
		assertThat(loaded.hrPayloadHash()).isEqualTo(hasher.hash(event));
		assertThat(loaded.lastHrEventAt()).isEqualTo(event.occurredAt());
		assertThat(loaded.transportFields()).isEqualTo(TransportOwnedFields.NONE);
		assertThat(loaded.transportVersion()).isZero();
	}

	@Test
	void bucketColumnUsesTheSameFunctionAsTheNightlyDiff() {
		newEmployee(create(1, "BLR-01", "MORNING"));

		Integer bucket = jdbc.queryForObject("SELECT bucket FROM employee WHERE employee_id = ?", Integer.class,
				EMPLOYEE.value());

		assertThat(bucket).isEqualTo(buckets.bucketOf(EMPLOYEE)).isEqualTo(40);
	}

	@Test
	void anotherTenantCannotSeeTheEmployee() {
		newEmployee(create(1, "BLR-01", "MORNING"));

		Optional<Employee> lockedByOtherTenant = tx.execute(status -> employees.findForUpdate(GLOBEX, EMPLOYEE));

		assertThat(employees.find(GLOBEX, EMPLOYEE)).isEmpty();
		assertThat(lockedByOtherTenant).isEmpty();
	}

	@Test
	void writesRefuseAnEmployeeOfAnotherTenant() {
		var event = create(1, "BLR-01", "MORNING");
		Employee acmeEmployee = Employee.createFrom(event, hasher.hash(event));

		// @Repository translates exceptions into Spring's DataAccessException hierarchy
		assertThatThrownBy(() -> employees.insert(GLOBEX, acmeEmployee))
				.isInstanceOf(InvalidDataAccessApiUsageException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> employees.updateHrFields(GLOBEX, acmeEmployee))
				.isInstanceOf(InvalidDataAccessApiUsageException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class);
		assertThat(employees.find(GLOBEX, EMPLOYEE)).isEmpty();
	}

	@Test
	void lockingReadRequiresATransaction() {
		assertThatThrownBy(() -> employees.findForUpdate(TENANT, EMPLOYEE))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void lockingReadReturnsTheCurrentRow() {
		newEmployee(create(1, "BLR-01", "MORNING"));

		Employee locked = tx.execute(status -> employees.findForUpdate(TENANT, EMPLOYEE).orElseThrow());

		assertThat(locked.hrVersion()).isEqualTo(1);
		assertThat(locked.hrFields().officeCode()).isEqualTo("BLR-01");
	}

	@Test
	void newerHrVersionIsWritten() {
		Employee employee = newEmployee(create(1, "BLR-01", "MORNING"));
		var v2 = update(2, "HYD-02", "NIGHT");
		employee.applyHr(v2, hasher.hash(v2));

		assertThat(employees.updateHrFields(TENANT, employee)).isTrue();
		assertThat(stored().hrVersion()).isEqualTo(2);
		assertThat(stored().hrFields().officeCode()).isEqualTo("HYD-02");
	}

	@Test
	void hrWriteIsRefusedWhenTheStoredVersionIsNotOlder() {
		Employee first = newEmployee(create(1, "BLR-01", "MORNING"));
		Employee second = stored();
		var v2a = update(2, "HYD-02", "NIGHT");
		var v2b = update(2, "BLR-01", "NIGHT");
		first.applyHr(v2a, hasher.hash(v2a));
		second.applyHr(v2b, hasher.hash(v2b));

		assertThat(employees.updateHrFields(TENANT, first)).isTrue();
		assertThat(employees.updateHrFields(TENANT, second)).isFalse();
		assertThat(stored().hrFields().officeCode()).isEqualTo("HYD-02");
	}

	@Test
	void deactivationIsStored() {
		Employee employee = newEmployee(create(1, "BLR-01", "MORNING"));
		employee.applyHr(deactivate(2), hasher.hash(deactivate(2)));

		employees.updateHrFields(TENANT, employee);

		assertThat(stored().status()).isEqualTo(EmployeeStatus.INACTIVE);
	}

	@Test
	void hrWritesNeverTouchTransportColumns() {
		Employee employee = newEmployee(create(1, "BLR-01", "MORNING"));
		employee.applyTransport(new TransportOwnedFields("Gate 2, Tech Park", "wheelchair"), 0);
		employees.updateTransportFields(TENANT, employee, 0);

		Employee fromDb = stored();
		var v2 = update(2, "HYD-02", "NIGHT");
		fromDb.applyHr(v2, hasher.hash(v2));
		employees.updateHrFields(TENANT, fromDb);

		assertThat(stored().transportFields().pickupPoint()).isEqualTo("Gate 2, Tech Park");
		assertThat(stored().transportFields().specialAssistance()).isEqualTo("wheelchair");
		assertThat(stored().transportVersion()).isEqualTo(1);
	}

	@Test
	void transportWriteWithAnOutdatedExpectedVersionIsRefused() {
		newEmployee(create(1, "BLR-01", "MORNING"));
		Employee adminA = stored();
		Employee adminB = stored();
		adminA.applyTransport(new TransportOwnedFields("Gate 2", null), 0);
		adminB.applyTransport(new TransportOwnedFields("Gate 5", null), 0);

		assertThat(employees.updateTransportFields(TENANT, adminA, 0)).isTrue();
		assertThat(employees.updateTransportFields(TENANT, adminB, 0)).isFalse();
		assertThat(stored().transportFields().pickupPoint()).isEqualTo("Gate 2");
	}

	@Test
	void transportWritesDoNotMoveTheHrVersion() {
		Employee employee = newEmployee(create(4, "BLR-01", "MORNING"));
		employee.applyTransport(new TransportOwnedFields("Gate 2", null), 0);

		employees.updateTransportFields(TENANT, employee, 0);

		assertThat(stored().hrVersion()).isEqualTo(4);
	}

	@Test
	void countsActiveEmployeesPerTenant() {
		hire(TENANT, "E000001");
		Employee leaver = hire(TENANT, "E000002");
		hire(GLOBEX, "E000001");
		var exit = new DeactivateEmployee(new EventHeader(TENANT, "workday", leaver.employeeId(), 2, T0));
		leaver.applyHr(exit, hasher.hash(exit));
		employees.updateHrFields(TENANT, leaver);

		assertThat(employees.countActive(TENANT)).isEqualTo(1);
		assertThat(employees.countActive(GLOBEX)).isEqualTo(1);
	}

	private Employee hire(TenantId tenant, String employeeId) {
		return newEmployee(new CreateEmployee(new EventHeader(tenant, "workday", new EmployeeId(employeeId), 1, T0),
				fields("BLR-01", "DAY")));
	}
}
