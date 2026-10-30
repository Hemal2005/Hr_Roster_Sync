package com.moveinsync.rostersync.api.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.rostersync.application.ApplyOutcome;
import com.moveinsync.rostersync.domain.ErrorCode;

/**
 * The per-record report: position in the batch, employee id, outcome and, for a
 * rejection, the code. Never a field value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordResult(int index, String employeeId, ApplyOutcome outcome, ErrorCode errorCode) {
}
