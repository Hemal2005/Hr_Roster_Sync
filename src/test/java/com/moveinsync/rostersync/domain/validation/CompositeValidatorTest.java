package com.moveinsync.rostersync.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.moveinsync.rostersync.domain.CreateEmployee;
import com.moveinsync.rostersync.domain.DeactivateEmployee;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.EventHeader;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.UpdateEmployee;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CompositeValidatorTest {

	private static final TenantId ACME = new TenantId("acme");
	private static final TenantId GLOBEX = new TenantId("globex");
	private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
	private static final String EMAIL = "priyanka.r@acme.example";
	private static final String PHONE = "+919876543210";

	private static final OfficeDirectory OFFICES = (tenant, code) ->
			Map.of(ACME, Set.of("BLR-01", "HYD-02"), GLOBEX, Set.of("PUN-01"))
					.getOrDefault(tenant, Set.of())
					.contains(code);

	private final CompositeValidator validator = CompositeValidator.standard(OFFICES);

	private static IncomingHrRecord valid() {
		return new IncomingHrRecord(HrEventType.UPDATE, "E000042", 7L, T0,
				"Priyanka", "Raghunathan", EMAIL, PHONE, "E000001", "BLR-01", "NIGHT");
	}

	private static IncomingHrRecord withPhone(String phone) {
		var r = valid();
		return new IncomingHrRecord(r.type(), r.employeeId(), r.version(), r.occurredAt(), r.firstName(),
				r.lastName(), r.email(), phone, r.managerId(), r.officeCode(), r.shiftCode());
	}

	private static IncomingHrRecord withEmail(String email) {
		var r = valid();
		return new IncomingHrRecord(r.type(), r.employeeId(), r.version(), r.occurredAt(), r.firstName(),
				r.lastName(), email, r.phone(), r.managerId(), r.officeCode(), r.shiftCode());
	}

	private static IncomingHrRecord withOffice(String officeCode) {
		var r = valid();
		return new IncomingHrRecord(r.type(), r.employeeId(), r.version(), r.occurredAt(), r.firstName(),
				r.lastName(), r.email(), r.phone(), r.managerId(), officeCode, r.shiftCode());
	}

	private List<ErrorCode> codes(IncomingHrRecord record) {
		return validator.validate(ACME, record).stream().map(ValidationError::code).toList();
	}

	@Test
	void wellFormedRecordPasses() {
		assertThat(validator.validate(ACME, valid())).isEmpty();
	}

	@Test
	void lastNameAndManagerAreOptional() {
		var r = valid();
		var noOptional = new IncomingHrRecord(r.type(), r.employeeId(), r.version(), r.occurredAt(), r.firstName(),
				null, r.email(), r.phone(), null, r.officeCode(), r.shiftCode());

		assertThat(validator.validate(ACME, noOptional)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"+919876543210", "+442071838750", "+16502530000"})
	void acceptsValidE164Numbers(String phone) {
		assertThat(codes(withPhone(phone))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"12ab",              // not a number
			"9876543210",        // no country code
			"+91 98765 43210",   // formatted, not canonical E.164
			"+91-9876543210",    // separator
			"+1234",             // too short
			"+0919876543210",    // country code cannot start with 0
			"+910000000000",     // right shape, not a real Indian number
			"+9198765432101234"  // too long
	})
	void rejectsMalformedPhoneNumbers(String phone) {
		assertThat(codes(withPhone(phone))).containsExactly(ErrorCode.INVALID_PHONE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"no-at-sign", "two@@acme.example", "a@nodot", "spa ce@acme.example", "@acme.example"})
	void rejectsMalformedEmails(String email) {
		assertThat(codes(withEmail(email))).containsExactly(ErrorCode.INVALID_EMAIL);
	}

	@Test
	void rejectsOverlongEmail() {
		assertThat(codes(withEmail("a".repeat(250) + "@x.io"))).containsExactly(ErrorCode.INVALID_EMAIL);
	}

	@Test
	void rejectsUnknownOfficeCode() {
		assertThat(codes(withOffice("CHN-99"))).containsExactly(ErrorCode.UNKNOWN_OFFICE);
	}

	@Test
	void officeCodesAreCheckedPerTenant() {
		var puneRecord = withOffice("PUN-01");

		assertThat(validator.validate(GLOBEX, puneRecord)).isEmpty();
		assertThat(validator.validate(ACME, puneRecord))
				.extracting(ValidationError::code)
				.containsExactly(ErrorCode.UNKNOWN_OFFICE);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	void blankEmployeeIdIsMissing(String employeeId) {
		var r = valid();
		var record = new IncomingHrRecord(r.type(), employeeId, r.version(), r.occurredAt(), r.firstName(),
				r.lastName(), r.email(), r.phone(), r.managerId(), r.officeCode(), r.shiftCode());

		assertThat(validator.validate(ACME, record))
				.extracting(ValidationError::code, ValidationError::field)
				.containsExactly(tuple(ErrorCode.MISSING_REQUIRED_FIELD, "employeeId"));
	}

	@Test
	void reportsEveryMissingFieldAtOnce() {
		var empty = new IncomingHrRecord(HrEventType.CREATE, "E000042", null, null,
				null, null, null, null, null, null, null);

		assertThat(validator.validate(ACME, empty))
				.extracting(ValidationError::field)
				.containsExactlyInAnyOrder("version", "occurredAt", "firstName", "email", "phone", "officeCode",
						"shiftCode");
	}

	@Test
	void missingTypeIsReported() {
		var r = valid();
		var noType = new IncomingHrRecord(null, r.employeeId(), r.version(), r.occurredAt(), r.firstName(),
				r.lastName(), r.email(), r.phone(), r.managerId(), r.officeCode(), r.shiftCode());

		assertThat(validator.validate(ACME, noType))
				.extracting(ValidationError::field)
				.containsExactly("type");
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -3})
	void nonPositiveVersionIsInvalid(long version) {
		var r = valid();
		var record = new IncomingHrRecord(r.type(), r.employeeId(), version, r.occurredAt(), r.firstName(),
				r.lastName(), r.email(), r.phone(), r.managerId(), r.officeCode(), r.shiftCode());

		assertThat(codes(record)).containsExactly(ErrorCode.INVALID_VERSION);
	}

	@Test
	void deactivationOnlyNeedsTheHeader() {
		var deactivate = new IncomingHrRecord(HrEventType.DEACTIVATE, "E000042", 8L, T0,
				null, null, null, null, null, null, null);

		assertThat(validator.validate(ACME, deactivate)).isEmpty();
		assertThat(deactivate.toEvent(ACME, "workday"))
				.isEqualTo(new DeactivateEmployee(new EventHeader(ACME, "workday", new EmployeeId("E000042"), 8, T0)));
	}

	@Test
	void deactivationThatCarriesFieldsMustCarryAValidCompleteSet() {
		var partialAndInvalid = new IncomingHrRecord(HrEventType.DEACTIVATE, "E000042", 8L, T0,
				null, null, "garbage", "garbage", null, "CHN-99", null);

		assertThat(codes(partialAndInvalid)).contains(ErrorCode.MISSING_REQUIRED_FIELD, ErrorCode.INVALID_EMAIL,
				ErrorCode.INVALID_PHONE, ErrorCode.UNKNOWN_OFFICE);
	}

	@Test
	void deactivationWithFullStateKeepsItsFields() {
		var r = valid();
		var exit = new IncomingHrRecord(HrEventType.DEACTIVATE, r.employeeId(), r.version(), r.occurredAt(),
				r.firstName(), r.lastName(), r.email(), r.phone(), r.managerId(), r.officeCode(), r.shiftCode());

		assertThat(validator.validate(ACME, exit)).isEmpty();
		assertThat(((DeactivateEmployee) exit.toEvent(ACME, "workday")).carriesFullState()).isTrue();
	}

	@Test
	void collectsErrorsFromSeveralValidators() {
		var r = valid();
		var bad = new IncomingHrRecord(r.type(), r.employeeId(), r.version(), r.occurredAt(), r.firstName(),
				r.lastName(), "nope", "12ab", r.managerId(), "CHN-99", r.shiftCode());

		assertThat(codes(bad))
				.containsExactly(ErrorCode.INVALID_EMAIL, ErrorCode.INVALID_PHONE, ErrorCode.UNKNOWN_OFFICE);
	}

	@Test
	void errorsAndRecordToStringNeverContainTheRejectedValues() {
		var bad = withPhone("+91-98765-BAD");

		assertThat(validator.validate(ACME, bad).toString()).doesNotContain("98765", EMAIL, "Priyanka");
		assertThat(bad.toString()).contains("E000042").doesNotContain("98765", EMAIL, "Priyanka", "Raghunathan");
	}

	@Test
	void validRecordsBecomeTheMatchingEventType() {
		var r = valid();

		assertThat(r.toEvent(ACME, "workday")).isInstanceOf(UpdateEmployee.class);
		assertThat(new IncomingHrRecord(HrEventType.CREATE, "E1", 1L, T0, "A", null, EMAIL, PHONE, null, "BLR-01",
				"DAY").toEvent(ACME, "workday")).isInstanceOf(CreateEmployee.class);
		assertThat(new IncomingHrRecord(HrEventType.DEACTIVATE, "E1", 2L, T0, null, null, null, null, null, null,
				null).toEvent(ACME, "workday")).isInstanceOf(DeactivateEmployee.class);
	}

	@Test
	void eventKeepsTheSourceVersionAndTenant() {
		var event = valid().toEvent(ACME, "workday");

		assertThat(event.version()).isEqualTo(7);
		assertThat(event.tenantId()).isEqualTo(ACME);
		assertThat(event.sourceSystem()).isEqualTo("workday");
	}
}
