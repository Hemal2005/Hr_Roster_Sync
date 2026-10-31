package com.moveinsync.rostersync.api.ingest;

import com.moveinsync.rostersync.application.ApplyResult;
import com.moveinsync.rostersync.application.ApplyService;
import com.moveinsync.rostersync.domain.EmployeeField;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.FieldOwner;
import com.moveinsync.rostersync.domain.FieldOwnershipPolicy;
import com.moveinsync.rostersync.domain.OwnershipCheck;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.validation.HrEventType;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Receives up to 1,000 HR events and reports an outcome per record. Every record is
 * parsed, validated and applied on its own, so the response is 200 with per-record
 * results even when some records are rejected.
 */
@RestController
@RequestMapping("/api/v1/hr")
class IngestController {

	private static final Logger log = LoggerFactory.getLogger(IngestController.class);

	private final ApplyService applyService;
	private final ObjectMapper json;
	private final MeterRegistry meters;
	private final FieldOwnershipPolicy ownership = new FieldOwnershipPolicy();

	IngestController(ApplyService applyService, ObjectMapper json, MeterRegistry meters) {
		this.applyService = applyService;
		this.json = json;
		this.meters = meters;
	}

	@PostMapping(path = "/events:batch", consumes = MediaType.APPLICATION_JSON_VALUE)
	BatchResponse ingest(TenantId tenantId, @Valid @RequestBody BatchRequest batch) {
		List<RecordResult> results = new ArrayList<>(batch.events().size());
		for (int index = 0; index < batch.events().size(); index++) {
			results.add(ingestOne(tenantId, batch.sourceSystem(), index, batch.events().get(index)));
		}
		return new BatchResponse(results);
	}

	private RecordResult ingestOne(TenantId tenantId, String sourceSystem, int index, JsonNode node) {
		if (node == null || !node.isObject()) {
			return rejected(tenantId, index, null, ErrorCode.MALFORMED_RECORD);
		}
		HrEventRequest request;
		try {
			request = json.treeToValue(node, HrEventRequest.class);
		} catch (JacksonException e) {
			return rejected(tenantId, index, null, ErrorCode.MALFORMED_RECORD);
		}
		HrEventType type;
		try {
			type = request.type() == null ? null : HrEventType.valueOf(request.type().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return rejected(tenantId, index, request.employeeId(), ErrorCode.INVALID_EVENT_TYPE);
		}
		countForeignFields(tenantId, node);
		ApplyResult result = applyService.apply(tenantId, sourceSystem, request.toIncoming(type));
		return new RecordResult(index, request.employeeId(), result.outcome(), result.errorCode());
	}

	/**
	 * An HR payload carrying transport-owned fields (pickup point, special assistance) is
	 * not rejected; those values are simply never mapped, and each occurrence is counted.
	 */
	private void countForeignFields(TenantId tenantId, JsonNode node) {
		Set<String> extra = new HashSet<>(node.propertyNames());
		extra.removeAll(HrEventRequest.FIELD_NAMES);
		if (extra.isEmpty()) {
			return;
		}
		OwnershipCheck check = ownership.check(FieldOwner.HR, extra);
		for (EmployeeField field : check.foreign()) {
			meters.counter("hr.owned.field.violation", "tenant", tenantId.value(), "field", field.wireName())
					.increment();
		}
		if (check.hasViolations()) {
			log.warn("hr payload carried fields it does not own: {}", check.foreign());
		}
	}

	/**
	 * A record rejected before it reaches the apply service still counts in hr_events, so
	 * the rejection rate on the dashboard includes unreadable records.
	 */
	private RecordResult rejected(TenantId tenantId, int index, String employeeId, ErrorCode code) {
		ApplyResult result = ApplyResult.rejected(code);
		meters.counter("hr.events", "tenant", tenantId.value(), "outcome", result.outcome().name(), "code", code.name())
				.increment();
		return new RecordResult(index, employeeId, result.outcome(), code);
	}
}
