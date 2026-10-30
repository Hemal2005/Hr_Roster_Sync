package com.moveinsync.rostersync.application.reconciliation;

import com.moveinsync.rostersync.domain.DomainException;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.TenantId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The entry points behind the API: accept an export and run it in the background, show
 * a run, and record an operator's decision. Runs execute one at a time on their own
 * thread, so a 200,000-row file never holds up a web request.
 */
@Service
public class ReconciliationRunner {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

	private final ReconciliationStore store;
	private final ReconciliationService service;
	private final ExecutorService executor;

	ReconciliationRunner(ReconciliationStore store, ReconciliationService service,
			@Qualifier("reconciliationExecutor") ExecutorService executor) {
		this.store = store;
		this.service = service;
		this.executor = executor;
	}

	/**
	 * Checks the export's header synchronously (it must name the caller's tenant), records
	 * a RUNNING run and plans it in the background. The upload is deleted afterwards.
	 */
	public UUID start(TenantId tenantId, String fileName, Path upload, String submittedBy) {
		ExportReader.Header header;
		try {
			header = ExportReader.readHeader(upload);
			if (!header.tenantId().equals(tenantId.value())) {
				throw new DomainException(ErrorCode.EXPORT_TENANT_MISMATCH);
			}
		} catch (RuntimeException e) {
			delete(upload);
			throw e;
		}
		UUID runId = UUID.randomUUID();
		store.createRun(runId, tenantId, fileName, header.asOf(), submittedBy);
		executor.submit(() -> {
			try {
				service.execute(runId, tenantId, header.asOf(), upload);
			} finally {
				delete(upload);
			}
		});
		return runId;
	}

	public ReconciliationRun get(TenantId tenantId, UUID runId) {
		return store.find(tenantId, runId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
	}

	public List<ReconciliationStore.RunError> errors(TenantId tenantId, UUID runId, int page) {
		get(tenantId, runId);
		return store.errors(tenantId, runId, Math.max(0, page), 100);
	}

	/** Approves a run awaiting approval and applies its plan in the background. */
	public ReconciliationRun approve(TenantId tenantId, UUID runId, String operator) {
		ReconciliationRun run = get(tenantId, runId);
		if (!store.recordDecision(runId, RunStatus.APPLYING, operator)) {
			throw new DomainException(ErrorCode.RUN_NOT_AWAITING_APPROVAL);
		}
		log.info("run approved by operator; applying");
		executor.submit(() -> {
			try {
				service.applyPlan(runId, tenantId, run.asOf());
			} catch (RuntimeException e) {
				store.fail(runId, e.getClass().getSimpleName());
				log.warn("applying approved run failed {}", e.getClass().getName());
			}
		});
		return get(tenantId, runId);
	}

	/** Rejects a run awaiting approval; its plan is never applied. */
	public ReconciliationRun reject(TenantId tenantId, UUID runId, String operator) {
		get(tenantId, runId);
		if (!store.recordDecision(runId, RunStatus.REJECTED, operator)) {
			throw new DomainException(ErrorCode.RUN_NOT_AWAITING_APPROVAL);
		}
		log.info("run rejected by operator; nothing applied");
		return get(tenantId, runId);
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.warn("could not delete uploaded export");
		}
	}
}
