package com.moveinsync.rostersync.application.reconciliation;

import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.BrakeDecision;
import com.moveinsync.rostersync.domain.reconciliation.BrakeThreshold;
import com.moveinsync.rostersync.domain.reconciliation.SafetyBrake;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs one nightly reconciliation: plan, then let the safety brake decide. If it passes,
 * the plan is applied; if it trips, the run waits for an operator and nothing changes.
 * Planning never changes an employee, so "deactivate nobody until a human approves" holds
 * by construction.
 */
@Service
public class ReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	private final ReconciliationStore store;
	private final ReconciliationPlanner planner;
	private final ReconciliationApplier applier;
	private final MeterRegistry meters;
	private final BrakeThreshold threshold;
	private final SafetyBrake brake = new SafetyBrake();

	ReconciliationService(ReconciliationStore store, ReconciliationPlanner planner, ReconciliationApplier applier,
			MeterRegistry meters, @Value("${rostersync.reconciliation.brake-threshold:0.30}") String threshold) {
		this.store = store;
		this.planner = planner;
		this.applier = applier;
		this.meters = meters;
		this.threshold = BrakeThreshold.of(threshold);
	}

	/** Plans the run and, unless the brake trips, applies it. Returns the final status. */
	public RunStatus execute(UUID runId, TenantId tenantId, Instant asOf, Path export) {
		MDC.put("tenantId", tenantId.value());
		MDC.put("runId", runId.toString());
		try {
			long started = System.nanoTime();
			PlanSummary plan = planner.plan(runId, tenantId, asOf, export);
			store.recordPlan(runId, plan);
			BrakeDecision decision = brake.evaluate(plan.activeBefore(), plan.toDeactivate(), plan.trailerOk(), threshold);
			store.recordBrake(runId, decision);
			recordDuration(tenantId, "plan", decision instanceof BrakeDecision.Trip ? "TRIPPED" : "PASSED", started);
			if (decision instanceof BrakeDecision.Trip trip) {
				meters.counter("reconciliation.brake.tripped", "tenant", tenantId.value(), "reason", trip.reason().name())
						.increment();
				log.warn("safety brake tripped reason={} missing={} activeBefore={} ratio={}; awaiting operator",
						trip.reason(), trip.toDeactivate(), trip.activeBefore(), trip.missingRatio());
				return RunStatus.AWAITING_APPROVAL;
			}
			store.transition(runId, RunStatus.PLANNED, RunStatus.APPLYING);
			return applyPlan(runId, tenantId, asOf);
		} catch (RuntimeException e) {
			store.fail(runId, e.getClass().getSimpleName());
			log.warn("reconciliation failed {}", e.getClass().getName());
			return RunStatus.FAILED;
		} finally {
			MDC.remove("tenantId");
			MDC.remove("runId");
		}
	}

	/** Applies a run that is already APPLYING (after planning, or after an operator approved it). */
	public RunStatus applyPlan(UUID runId, TenantId tenantId, Instant asOf) {
		long started = System.nanoTime();
		ReconciliationApplier.Outcome outcome = applier.apply(runId, tenantId, asOf);
		store.recordApplied(runId, outcome.applied(), outcome.skipped());
		recordDuration(tenantId, "apply", "COMPLETED", started);
		log.info("reconciliation applied={} skipped={}", outcome.applied(), outcome.skipped());
		return RunStatus.COMPLETED;
	}

	private void recordDuration(TenantId tenantId, String phase, String result, long startedNanos) {
		Timer.builder("reconciliation.duration")
				.description("Time to plan a nightly export, or to apply its plan")
				.tags("tenant", tenantId.value(), "phase", phase, "result", result)
				.register(meters)
				.record(Duration.ofNanos(System.nanoTime() - startedNanos));
	}
}
