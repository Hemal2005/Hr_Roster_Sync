package com.moveinsync.rostersync.api.reconciliation;

import com.moveinsync.rostersync.application.reconciliation.ReconciliationRun;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationRunner;
import com.moveinsync.rostersync.application.reconciliation.ReconciliationStore.RunError;
import com.moveinsync.rostersync.domain.TenantId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Nightly reconciliation. Uploading returns at once with a run id; the run is planned in
 * the background and either completes or waits for an operator.
 */
@RestController
@RequestMapping("/api/v1/reconciliations")
class ReconciliationController {

	record Accepted(UUID runId, String status) {
	}

	private final ReconciliationRunner runner;

	ReconciliationController(ReconciliationRunner runner) {
		this.runner = runner;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<Accepted> upload(TenantId tenantId, @RequestPart("file") MultipartFile file,
			@AuthenticationPrincipal Jwt token) {
		UUID runId = runner.start(tenantId, file.getOriginalFilename() == null ? "export.csv" : file.getOriginalFilename(),
				saveUpload(file), token.getSubject());
		return ResponseEntity.accepted()
				.location(URI.create("/api/v1/reconciliations/" + runId))
				.body(new Accepted(runId, "RUNNING"));
	}

	@GetMapping("/{runId}")
	ReconciliationRun get(TenantId tenantId, @PathVariable UUID runId) {
		return runner.get(tenantId, runId);
	}

	/** Per-line problems: line number, employee id if known and code, never the row's values. */
	@GetMapping("/{runId}/errors")
	List<RunError> errors(TenantId tenantId, @PathVariable UUID runId, @RequestParam(defaultValue = "0") int page) {
		return runner.errors(tenantId, runId, page);
	}

	@PostMapping("/{runId}/approve")
	ResponseEntity<ReconciliationRun> approve(TenantId tenantId, @PathVariable UUID runId,
			@AuthenticationPrincipal Jwt token) {
		return ResponseEntity.accepted().body(runner.approve(tenantId, runId, token.getSubject()));
	}

	@PostMapping("/{runId}/reject")
	ReconciliationRun reject(TenantId tenantId, @PathVariable UUID runId, @AuthenticationPrincipal Jwt token) {
		return runner.reject(tenantId, runId, token.getSubject());
	}

	private static Path saveUpload(MultipartFile file) {
		try {
			Path upload = Files.createTempFile("export-upload-", ".csv");
			file.transferTo(upload);
			return upload;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
