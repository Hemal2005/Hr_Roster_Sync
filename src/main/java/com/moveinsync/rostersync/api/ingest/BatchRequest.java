package com.moveinsync.rostersync.api.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Events stay as raw JSON here and are parsed one by one, so a record with a wrong type
 * in one field is rejected on its own instead of failing the whole request.
 */
public record BatchRequest(
		@NotBlank String sourceSystem,
		@NotNull @Size(min = 1, max = BatchRequest.MAX_EVENTS) List<JsonNode> events) {

	public static final int MAX_EVENTS = 1000;
}
