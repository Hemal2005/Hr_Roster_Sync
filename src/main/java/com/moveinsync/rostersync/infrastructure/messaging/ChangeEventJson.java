package com.moveinsync.rostersync.infrastructure.messaging;

import com.moveinsync.rostersync.domain.ChangeEvent;
import com.moveinsync.rostersync.domain.ChangeEventType;
import com.moveinsync.rostersync.domain.EmployeeField;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.EmployeeStatus;
import com.moveinsync.rostersync.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The wire format of a change event, shared by the outbox (writer) and the consumer
 * (reader). Field names use their public wire names.
 */
@Component
public class ChangeEventJson {

	record Payload(String eventId, String tenantId, String employeeId, String type, String status, long hrVersion,
			long transportVersion, List<String> changedFields, String occurredAt) {
	}

	private final ObjectMapper json;

	ChangeEventJson(ObjectMapper json) {
		this.json = json;
	}

	public String write(ChangeEvent event) {
		List<String> fields = event.changedFields().stream().sorted().map(EmployeeField::wireName).toList();
		return json.writeValueAsString(new Payload(event.eventId().toString(), event.tenantId().value(),
				event.employeeId().value(), event.type().name(), event.status().name(), event.hrVersion(),
				event.transportVersion(), fields, event.occurredAt().toString()));
	}

	public ChangeEvent read(String text) {
		Payload p = json.readValue(text, Payload.class);
		Set<EmployeeField> fields = p.changedFields().stream()
				.map(name -> EmployeeField.fromWireName(name).orElseThrow(
						() -> new IllegalArgumentException("unknown field in change event")))
				.collect(Collectors.toSet());
		return new ChangeEvent(UUID.fromString(p.eventId()), new TenantId(p.tenantId()),
				new EmployeeId(p.employeeId()), ChangeEventType.valueOf(p.type()), EmployeeStatus.valueOf(p.status()),
				p.hrVersion(), p.transportVersion(), fields, Instant.parse(p.occurredAt()));
	}
}
