# Requirements traceability

Every line of the case study mapped to a requirement and the automated test (or document) that proves it. A requirement counts as done only when its proof passes.

**Status:** **Done**: the proof exists and passes. **Partial**: the core rule is proven by a unit test; the end-to-end proof listed is still to come. **Planned**: not built yet.

Test paths are under `src/test/java/com/moveinsync/rostersync/`.

| ID | Case-study source | Requirement | Proof | Status |
|---|---|---|---|---|
| R-ING-1 | I. Events | Accept create / update / deactivate events, each with the source version | [`HrEventTest`](../src/test/java/com/moveinsync/rostersync/domain/HrEventTest.java), [`EmployeeTest`](../src/test/java/com/moveinsync/rostersync/domain/EmployeeTest.java); `IngestControllerIT` | Partial |
| R-ING-2 | I. "near real time" API | Batch endpoint with per-record results | `IngestControllerIT.batchReturnsPerRecordResults` | Planned |
| R-VAL-1 | I. Validate at the boundary | Reject malformed phone (E.164), unknown office code, missing required fields, bad email | [`CompositeValidatorTest`](../src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java); office lookup per tenant: [`OfficeDirectoryIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/OfficeDirectoryIT.java) | Done |
| R-VAL-2 | I. One bad record never fails a batch | 1 bad record in 100 → 99 applied, 1 rejected with a code | `IngestControllerIT.oneBadRecordDoesNotFailBatch` | Planned |
| R-VAL-3 | I. Per-record error report | Report has index, employee id, error code, and no values | [`CompositeValidatorTest.errorsAndRecordToStringNeverContainTheRejectedValues`](../src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java); `ErrorReportContainsNoPiiTest` | Partial |
| R-ORD-1 | II. Per-employee order | Apply in version order; stale ignored | [`VersionPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java); `ApplyServiceIT.staleIgnored` | Partial |
| R-ORD-2 | II. Duplicate is a no-op | Same version + same payload → nothing changes, no outbox row | [`VersionPolicyTest.comparesIncomingVersionWithStored`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java); `ApplyServiceIT.duplicateIsNoOp` | Partial |
| R-ORD-3 | Example: v7 transfer before v6 shift | Final state: new office + v7 shift | [`VersionPolicyTest.transferV7BeforeShiftV6EndsInNewOfficeWithV7Shift`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java); `ExampleUseCaseIT.transferV7BeforeShiftV6` | Partial |
| R-IDEM-1 | Key points: idempotency key + version comparison | Key `(tenant_id, source_system, employee_id, version)`; rule in [`VersionPolicy`](../src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java) | README + `VersionPolicy` Javadoc | Partial |
| R-IDEM-2 | Key points: replay the same feed twice, nothing changes | 1,000-event feed twice; DB snapshot + outbox count identical | [`VersionPolicyTest.replayingTheSameFeedChangesNothingTheSecondTime`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java); [`SchemaIT.theSameAppliedChangeCannotBeLoggedTwice`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java); `ReplayFeedTwiceIT` | Partial |
| R-OWN-1 | II. Field ownership | HR owns name, email, manager, office (+ phone, shift, status, by assumption); transport owns pickup point, special assistance | [`FieldOwnershipPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicyTest.java) | Done |
| R-OWN-2 | II. HR never overwrites a transport field | HR update with pickup point → pickup unchanged, violation counted | [`FieldOwnershipPolicyTest.hrUpdatesNeverChangeTransportFields`](../src/test/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicyTest.java); [`EmployeeRepositoryIT.hrWritesNeverTouchTransportColumns`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java); `ApplyServiceIT.hrCannotOverwriteTransportFields` | Partial |
| R-OWN-3 | II. The conflict rule is written down | Rule in README + design note | [`FieldOwnershipPolicy`](../src/main/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicy.java) Javadoc; README; design note | Partial |
| R-REC-1 | III. Efficient diff, not fully in memory | Bucketed diff | [`BucketAssignerTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/BucketAssignerTest.java), [`PayloadHasherTest`](../src/test/java/com/moveinsync/rostersync/domain/PayloadHasherTest.java); `ReconciliationDifferTest` | Partial |
| R-REC-2 | Key points: 200k rows < 60 s, bounded memory | 200k file vs 200k DB rows at `-Xmx256m` | `ReconciliationBenchmarkIT` | Planned |
| R-BRK-1 | III. Safety brake at 30% | Missing active / active before ≥ threshold → awaiting approval, zero deactivations | [`SafetyBrakeTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrakeTest.java); `ReconciliationIT.brakeStopsRun` | Partial |
| R-BRK-2 | Key points: threshold configurable | `rostersync.reconciliation.brake-threshold`, default 0.30 | [`SafetyBrakeTest.thresholdConfigurable`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrakeTest.java); config wiring | Partial |
| R-BRK-3 | Example: file cut at 120k rows | Brake trips, nobody deactivated, operator sees ~80,000 missing | [`SafetyBrakeTest.fileCutAt120kRowsTripsAndReportsAbout80kMissing`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrakeTest.java); `ExampleUseCaseIT.truncatedFileAt120k` | Partial |
| R-BRK-4 | III. Until a human approves | Operator approve → plan applied; reject → nothing | `ReconciliationIT.operatorApproveApplies` | Planned |
| R-BRK-5 | III. Alert an operator | Metric + WARN log with run id + Prometheus alert rule | `ReconciliationIT`, `ops/prometheus/alerts.yml` | Planned |
| R-OUT-1 | IV. Outbox pattern | Event row written in the same transaction as the update | `OutboxIT.eventWrittenInSameTransaction` | Planned |
| R-OUT-2 | Key points: crash between DB write and publish | Publisher fails after commit; relay restarts; event delivered; consumer effect once | `OutboxCrashRecoveryIT` | Planned |
| R-DEA-1 | IV. Deactivation side effects | Future bookings cancelled, seats freed, past untouched, idempotent on redelivery | `DeactivationConsumerIT` | Planned |
| R-PII | Key points: no personal data in logs | Capture all logs during the integration suite; no seeded personal values appear | toString tests in [`EmployeeTest`](../src/test/java/com/moveinsync/rostersync/domain/EmployeeTest.java), [`HrOwnedFieldsTest`](../src/test/java/com/moveinsync/rostersync/domain/HrOwnedFieldsTest.java), [`FieldOwnershipPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicyTest.java); `NoPiiInLogsIT` | Partial |
| R-AUTH-1 | Plus 1: roles | EMPLOYEE (self only), ADMIN, OPERATOR, HR_INTEGRATION; each endpoint's role tested | `SecurityIT` | Planned |
| R-AUTH-2 | Plus 1: tenant isolation | Tenant A cannot read or modify tenant B → 404 | Storage level: [`EmployeeRepositoryIT.anotherTenantCannotSeeTheEmployee`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java), [`SchemaIT.anotherTenantsOfficeCannotBeUsed`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java); API level: `TenantIsolationIT` | Partial |
| R-COST | Plus 2: time and space | Big-O for apply, diff, brake, relay; where it stops scaling | README + benchmark | Planned |
| R-FAIL | Plus 3: failure cases | Crash, broker outage, duplicates, out-of-order, partial failure; at least one failure-injection test | `OutboxCrashRecoveryIT`, `BrokerDownIT` | Planned |
| R-CONC | Advanced track: concurrency test | 32 threads apply shuffled versions 1..200 for one employee → final 200, one applied outcome per version | [`VersionPolicyTest.anyArrivalOrderConvergesOnTheHighestVersion`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java) (single-threaded); `ConcurrentApplyIT` | Partial |
| R-OOP | Plus 4: OOP and layering | Sealed events, validator interface, owned-field inheritance, framework-free domain, infrastructure implementing domain ports | [`ArchitectureTest`](../src/test/java/com/moveinsync/rostersync/ArchitectureTest.java) | Partial |
| R-TRADE | Plus 5: trade-offs | Consistency vs availability vs latency; what was left out | README + design note | Planned |
| R-MON | Plus 6: monitoring | Ingest lag, error rate, rejections by code, stale, duplicate, retries, outbox depth and oldest age, consumer lag, reconciliation duration | `MetricsIT` | Planned |
| R-CACHE | Plus 7: caching | Caffeine caches + staleness statement | `OfficeCacheTest` | Planned |
| R-ERR | Plus 8: error handling | ProblemDetail, stable codes, no personal data | `GlobalExceptionHandlerTest` | Planned |
| R-ADV-1 | Advanced track: measured performance | Load test with throughput, p50, p99 + machine specs | `loadtest/ingest.js` | Planned |
| R-ADV-2 | Advanced track: design note | Architecture diagram, sequence diagram, failure-mode table | `docs/design-note.md` | Planned |
| R-ADV-3 | Advanced track: one-command run | `docker compose up --build` starts everything | README | Planned |
| R-SUB | Instructions 7–8 | Meaningful commit history; demo | `git log` | Partial |
