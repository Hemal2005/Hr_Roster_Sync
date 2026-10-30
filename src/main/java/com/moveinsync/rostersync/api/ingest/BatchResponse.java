package com.moveinsync.rostersync.api.ingest;

import java.util.List;

public record BatchResponse(List<RecordResult> results) {
}
