# Problem-by-problem test catalogue

For each of the twelve problems in the README, this page shows:
1. **the data**: which tables and columns are involved, with example rows before and after;
2. **the request**: the URL to call, the example payload and the response you get back;
3. **the tests**: a direct GitHub link to every test method that proves the rule, with what it proves and the command to run it.

Back to the [README](../README.md) · the step-by-step [testing guide](testing-guide.md) · the [traceability matrix](traceability.md).

| # | Problem | Main API URL | Key test |
|---|---|---|---|
| 1 | [A bad record must not block the good ones](#1-a-bad-record-must-not-block-the-good-ones) | `POST /api/v1/hr/events:batch` | [`IngestControllerIT.oneBadRecordDoesNotFailBatch`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L90) |
| 2 | [The same change arrives twice](#2-the-same-change-arrives-twice) | `POST /api/v1/hr/events:batch` | [`ReplayFeedTwiceIT.replayFeedTwiceChangesNothing`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ReplayFeedTwiceIT.java#L63) |
| 3 | [Changes arrive out of order, or at the same moment](#3-changes-arrive-out-of-order-or-at-the-same-moment) | `POST /api/v1/hr/events:batch` | [`ExampleUseCaseIT.transferV7BeforeShiftV6`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java#L54) |
| 4 | [HR and the transport team edit the same employee](#4-hr-and-the-transport-team-edit-the-same-employee) | `PATCH /api/v1/employees/{id}/transport` | [`TransportPatchIT.laterHrUpdatesKeepTheTransportFields`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java#L129) |
| 5 | [Comparing 200,000 employees every night without running out of memory](#5-comparing-200000-employees-every-night-without-running-out-of-memory) | `POST /api/v1/reconciliations` | [`ReconciliationBenchmarkIT.fullNightFitsTheBudget`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ReconciliationBenchmarkIT.java#L55) |
| 6 | [A broken nightly file must not deactivate thousands of people](#6-a-broken-nightly-file-must-not-deactivate-thousands-of-people) | `POST /api/v1/reconciliations/{id}/approve` | [`ExampleUseCaseIT.truncatedFileAt120kDeactivatesNobody`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java#L78) |
| 7 | [Every change must reach downstream systems, even across a crash](#7-every-change-must-reach-downstream-systems-even-across-a-crash) | `GET /actuator/prometheus` | [`OutboxCrashRecoveryIT.crashAfterPublishBeforeMarkingResendsAndTheConsumerActsOnce`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/OutboxCrashRecoveryIT.java#L140) |
| 8 | [People who leave must lose their cab bookings](#8-people-who-leave-must-lose-their-cab-bookings) | `GET /api/v1/bookings?employeeId=` | [`DeactivationConsumerIT.lateCopyOfAnOldExitDoesNotCancelBookingsMadeAfterRehire`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java#L126) |
| 9 | [Personal data must never leak](#9-personal-data-must-never-leak) | `POST /api/v1/hr/events:batch` | [`NoPiiInLogsIT.noPersonalValueReachesLogsMetricsOrErrorBodies`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/NoPiiInLogsIT.java#L93) |
| 10 | [One client never sees another's data; each person only what their role allows](#10-one-client-never-sees-anothers-data-each-person-only-what-their-role-allows) | `GET /api/v1/employees/{id}` | [`TenantIsolationIT.anotherTenantsEmployeeLooksExactlyLikeAMissingOne`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java#L61) |
| 11 | [On-call must see trouble at 3 AM](#11-on-call-must-see-trouble-at-3-am) | `GET /actuator/prometheus` | [`MetricsIT.everyMonitoringSeriesIsExposed`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/MetricsIT.java#L85) |
| 12 | [It must stay fast, and reads must not hammer the database](#12-it-must-stay-fast-and-reads-must-not-hammer-the-database) | `GET /api/v1/employees/{id}` | [`EmployeeReadCacheIT.aStaleETagFromTheCacheCannotOverwriteANewerTransportEdit`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/EmployeeReadCacheIT.java#L82) |

## Before you start

**The data model in one table.** Every table's key starts with `tenant_id` (the client), so one client's rows can never be addressed by another. Schema: [`src/main/resources/db/migration/`](https://github.com/Hemal2005/Hr_Roster_Sync/tree/main/src/main/resources/db/migration); diagram: README [Data model](../README.md#data-model).

| Table | One row is | Key columns | Used by problems |
|---|---|---|---|
| `tenant`, `office` | a client; one of its offices (`active` true/false) | `(tenant_id, code)` | 1, 10, 12 |
| `employee` | one employee of one client | `(tenant_id, employee_id)`; HR part: `first_name … status`, `hr_version`, `hr_payload_hash`, `last_hr_event_at`; transport part: `pickup_point`, `special_assistance`, `transport_version`; `bucket` | all |
| `employee_event_log` | one applied HR change | unique `(tenant_id, source_system, employee_id, version)` | 2, 3 |
| `outbox` | one change event waiting to be, or already, published | `event_id`, `published_at`, `attempts`, `last_error_code` | 7, 8 |
| `trip`, `booking` | a cab trip and its seats; one employee's seat on it | `seats_booked ≤ capacity`; `status` BOOKED/CANCELLED, `cancel_reason` | 8 |
| `processed_message` | an event a consumer has already acted on | `(consumer, event_id)` | 8 |
| `reconciliation_run`, `_item`, `_error` | one nightly file; its planned changes; its bad lines (line + code only) | `status`, counts, `brake_reason` | 5, 6 |

**Start the system and load the helpers.** Everything below runs against the one-command stack. Start it fresh so your numbers match this page:
```bash
docker compose down -v && docker compose up -d --build --wait
bash
```
Then paste the helper block from the testing guide, [Module 0](testing-guide.md#module-0-start-fresh-and-load-the-helpers). It defines `tok`, `ev`, `send`, `show`, `night`, `upload`, `runinfo` and the tokens `$HR`, `$ADMIN`, `$OPS`, `$GLOBEX_HR`, `$GLOBEX_ADMIN`. The problems below build on each other in order (1 → 12), like a real day. Outputs are from a real run; only ids, trace ids and timestamps differ.

**Run any linked test yourself** (JDK 21; Docker for the `…IT` classes):
```bash
./mvnw test   -Dtest='ClassName#methodName'                                                   # a unit test (…Test)
./mvnw verify -Dit.test='ClassName#methodName' -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false   # an integration test (…IT)
```

---

## 1. A bad record must not block the good ones

**The rule.** Every record in a batch is read, validated and saved on its own. A bad record is rejected with a stable code; the good ones apply.

**The data.** The request is a batch of HR events. Each event carries the employee's full HR state:

| Field | Example | Rule | Rejection code |
|---|---|---|---|
| `type` | `CREATE` | `CREATE`, `UPDATE` or `DEACTIVATE` | `INVALID_EVENT_TYPE` |
| `employeeId` | `E1002` | required | `MISSING_REQUIRED_FIELD` |
| `version` | `1` | a positive whole number | `MALFORMED_RECORD` (not a number), `INVALID_VERSION` |
| `occurredAt` | `2026-10-30T08:00:00Z` | required, ISO-8601 | `MISSING_REQUIRED_FIELD` |
| `firstName`, `lastName` | `Asha`, `Rao` | first name required | `MISSING_REQUIRED_FIELD` |
| `email` | `e1002@acme.example` | a valid address | `INVALID_EMAIL` |
| `phone` | `+919876543210` | E.164, a real number for its country | `INVALID_PHONE` |
| `officeCode` | `BLR-01` | exists **and is active** for this client (`office` table) | `UNKNOWN_OFFICE` |
| `shiftCode` | `DAY` | required | `MISSING_REQUIRED_FIELD` |

Rows written: one `employee` row (+ one `employee_event_log` row, + one `outbox` row) **per valid record only**.

**The request.** `POST http://localhost:8080/api/v1/hr/events:batch`, role `HR_INTEGRATION`:
```json
{ "sourceSystem": "workday", "events": [
  { "type": "CREATE", "employeeId": "E1002", "version": 1, "occurredAt": "2026-10-30T08:00:00Z", "firstName": "Asha", "lastName": "Rao",
    "email": "e1002@acme.example", "phone": "+919876543210", "officeCode": "BLR-01", "shiftCode": "DAY" },
  { …same, "employeeId": "E1003", "email": "not-an-email" },
  { …same, "employeeId": "E1004", "phone": "98765" },
  { …same, "employeeId": "E1005", "officeCode": "PUN-03" },
  { …same, "employeeId": "E1006", "version": "abc" },
  { "type": "CREATE", "employeeId": "E1007", "version": 1, "occurredAt": "2026-10-30T08:00:00Z" },
  { …same, "type": "PROMOTE", "employeeId": "E1008" } ] }
```
```bash
send $HR "$(ev CREATE E1002 1 BLR-01 DAY)" "$(ev CREATE E1003 1 BLR-01 DAY ',"email":"not-an-email"')" \
  "$(ev CREATE E1004 1 BLR-01 DAY ',"phone":"98765"')" "$(ev CREATE E1005 1 PUN-03 DAY)" \
  "$(ev CREATE E1006 1 BLR-01 DAY ',"version":"abc"')" '{"type":"CREATE","employeeId":"E1007","version":1,"occurredAt":"2026-10-30T08:00:00Z"}' \
  "$(ev PROMOTE E1008 1 BLR-01 DAY)"
```
```json
{"results":[
  {"index":0,"employeeId":"E1002","outcome":"APPLIED"},
  {"index":1,"employeeId":"E1003","outcome":"REJECTED","errorCode":"INVALID_EMAIL"},
  {"index":2,"employeeId":"E1004","outcome":"REJECTED","errorCode":"INVALID_PHONE"},
  {"index":3,"employeeId":"E1005","outcome":"REJECTED","errorCode":"UNKNOWN_OFFICE"},
  {"index":4,"outcome":"REJECTED","errorCode":"MALFORMED_RECORD"},
  {"index":5,"employeeId":"E1007","outcome":"REJECTED","errorCode":"MISSING_REQUIRED_FIELD"},
  {"index":6,"employeeId":"E1008","outcome":"REJECTED","errorCode":"INVALID_EVENT_TYPE"}]}
```
Request-level errors (the whole body is unusable): more than 1,000 events or a missing `sourceSystem` → `400 INVALID_REQUEST` with `fields`; not JSON → `400 MALFORMED_REQUEST`.

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`IngestControllerIT.oneBadRecordDoesNotFailBatch`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L90) | 1 bad phone in 100 records → 99 applied, 1 `INVALID_PHONE` | Integration |
| [`IngestControllerIT.wronglyTypedFieldRejectsOnlyItsRecord`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L109) | `"version":"abc"` rejects only its own record | Integration |
| [`IngestControllerIT.unknownEventTypeRejectsOnlyItsRecord`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L121) | an unknown `type` rejects only its own record | Integration |
| [`IngestControllerIT.errorReportContainsNoPersonalData`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L141) | the per-record report never echoes the rejected values | Integration |
| [`IngestControllerIT.batchOverTheLimitIsRefusedWhole`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L174) | 1,001 events → `400` for the whole request | Integration |
| [`ApplyServiceIT.invalidRecordIsRejectedAndWritesNothing`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/ApplyServiceIT.java#L103) | a rejected record leaves no row in any table | Integration |
| [`CompositeValidatorTest.rejectsMalformedPhoneNumbers`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java#L95) | eight bad phone shapes, each `INVALID_PHONE` | Unit |
| [`CompositeValidatorTest.officeCodesAreCheckedPerTenant`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java#L116) | an office valid for one client is unknown for another | Unit |
| [`CompositeValidatorTest.reportsEveryMissingFieldAtOnce`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java#L139) | all missing fields are reported, not just the first | Unit |

Code: [`IngestController.ingestOne`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/api/ingest/IngestController.java#L61) (one record at a time) · [`CompositeValidator.standard`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/validation/CompositeValidator.java#L19) (the validators).

---

## 2. The same change arrives twice

**The rule.** HR's per-employee `version` identifies a change. Same version + same content = duplicate: nothing is written, no event is sent. Same version + different content = `VERSION_CONFLICT`: reported, never guessed.

**The data.** The content fingerprint `hr_payload_hash` (SHA-256 of status + the seven HR fields) makes "same content?" one comparison.

| `employee` row of E1001 | `hr_version` | `hr_payload_hash` | `shift_code` | `employee_event_log` rows |
|---|---|---|---|---|
| after the first send | 1 | `h1` | `DAY` | `(acme, workday, E1001, 1, APPLIED)` |
| after the identical resend | 1 | `h1` | `DAY` | unchanged: still one row |
| after "v1 but shift NIGHT" | 1 | `h1` | `DAY` | unchanged: the conflict wrote nothing |

The unique key `(tenant_id, source_system, employee_id, version)` on `employee_event_log` refuses a second row for the same change, even if the code had a bug.

**The request.** `POST http://localhost:8080/api/v1/hr/events:batch`, three times:
```bash
send $HR "$(ev CREATE E1001 1 BLR-01 DAY)"                          # first time
send $HR "$(ev CREATE E1001 1 BLR-01 DAY)"                          # exactly the same again
send $HR "$(ev CREATE E1001 1 BLR-01 DAY ',"shiftCode":"NIGHT"')"   # same version, different content
show E1001
```
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"DUPLICATE"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"REJECTED","errorCode":"VERSION_CONFLICT"}]}
{'employeeId': 'E1001', 'hrVersion': 1, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`ReplayFeedTwiceIT.replayFeedTwiceChangesNothing`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ReplayFeedTwiceIT.java#L63) | a shuffled 1,000-event feed sent twice: every table identical afterwards, column by column | Integration |
| [`ApplyServiceIT.duplicateIsNoOp`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/ApplyServiceIT.java#L85) | a duplicate writes nothing (`updated_at` unchanged) | Integration |
| [`ApplyServiceIT.sameVersionWithDifferentContentIsRejectedAsConflict`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/ApplyServiceIT.java#L95) | same version, different content → `VERSION_CONFLICT`, nothing written | Integration |
| [`SchemaIT.theSameAppliedChangeCannotBeLoggedTwice`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java#L67) | the database itself refuses a second log row for one change | Integration |
| [`OutboxIT.duplicateStaleAndRejectedRecordsWriteNoEvent`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/OutboxIT.java#L101) | no change event for a duplicate | Integration |
| [`VersionPolicyTest.comparesIncomingVersionWithStored`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java#L37) | the full decision table, row by row | Unit |
| [`PayloadHasherTest`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/PayloadHasherTest.java#L11) | the fingerprint is stable across releases and changes with every HR field | Unit |

Code: [`VersionPolicy.decide`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java#L23) · [`PayloadHasher.hash`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/PayloadHasher.java#L21).

---

## 3. Changes arrive out of order, or at the same moment

**The rule.** Newer version → apply; older version → `STALE`, ignored. Events carry the full HR state, so ignoring an older one loses nothing. Changes to one employee are serialised by a row lock.

**The data.** HR made v2 (shift → NIGHT), then v3 (transfer to HYD-02, still NIGHT). v3 arrives first.

| Step | Incoming | Stored before | Decision | `employee` after (`hr_version`, `office_code`, `shift_code`) |
|---|---|---|---|---|
| 1 | v3 `HYD-02`, `NIGHT` | v1 `BLR-01`, `DAY` | 3 > 1 → `APPLIED` | 3, `HYD-02`, `NIGHT` |
| 2 | v2 `BLR-01`, `NIGHT` | v3 | 2 < 3 → `STALE` | unchanged: 3, `HYD-02`, `NIGHT` |

**The request.** `POST http://localhost:8080/api/v1/hr/events:batch`:
```bash
send $HR "$(ev UPDATE E1001 3 HYD-02 NIGHT)"   # v3 arrives first
send $HR "$(ev UPDATE E1001 2 BLR-01 NIGHT)"   # v2 arrives late
show E1001
```
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"STALE"}]}
{'employeeId': 'E1001', 'hrVersion': 3, 'officeCode': 'HYD-02', 'shiftCode': 'NIGHT', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`ExampleUseCaseIT.transferV7BeforeShiftV6`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java#L54) | the case study's own example (v7 before v6) with its values | Integration |
| [`ConcurrentApplyIT.concurrentOutOfOrderVersionsConverge`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ConcurrentApplyIT.java#L48) | 32 threads send versions 1–200 shuffled for one employee: ends at v200, each version applied once, nothing lost; fails without the row lock | Integration (concurrency) |
| [`ApplyServiceIT.staleIgnored`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/ApplyServiceIT.java#L75) | an older version is `STALE` and writes nothing | Integration |
| [`ApplyServiceIT.exitWithFullStateArrivingFirstIsKeptAndOlderEventsAreStale`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/ApplyServiceIT.java#L130) | an exit that arrives before the joiner's earlier events still wins | Integration |
| [`EmployeeRepositoryIT.hrWriteIsRefusedWhenTheStoredVersionIsNotOlder`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java#L144) | the SQL `UPDATE … WHERE hr_version < :new` refuses an old write even without the lock | Integration |
| [`VersionPolicyTest.anyArrivalOrderConvergesOnTheHighestVersion`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java#L90) | 199 shuffled orders all end at the highest version | Unit |

Code: [`ApplyService.decideAndWrite`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/application/ApplyService.java#L192) (lock, decide, write, one transaction) · [`JdbcEmployeeRepository.findForUpdate`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcEmployeeRepository.java#L57).

---

## 4. HR and the transport team edit the same employee

**The rule.** Every field has one owner. HR owns name, email, phone, manager, office, shift and status; the transport team owns pickup point and special assistance. Each owner has its own version. An admin edit must name the version it was based on (`If-Match`).

**The data.**

| `employee` columns | Owner | Version column | Written by |
|---|---|---|---|
| `first_name`, `last_name`, `email`, `phone`, `manager_id`, `office_code`, `shift_code`, `status` | HR | `hr_version` | `POST /api/v1/hr/events:batch` |
| `pickup_point`, `special_assistance` | Transport admin | `transport_version` (sent back as the `ETag`) | `PATCH /api/v1/employees/{id}/transport` |

| Step | Request | Result | `hr_version` | `transport_version` | `pickup_point` |
|---|---|---|---|---|---|
| start | | | 3 | 0 | null |
| 1 | HR v4 with `"pickupPoint":"Gate 9"` | `APPLIED`; pickup **ignored**, counted | 4 | 0 | null |
| 2 | PATCH without `If-Match` | `428 PRECONDITION_REQUIRED` | 4 | 0 | null |
| 3 | PATCH `If-Match: "0"`, pickup + assistance | `200`, `ETag: "1"` | 4 | 1 | Gate 2, Tech Park |
| 4 | PATCH with the old `If-Match: "0"` | `412 TRANSPORT_VERSION_MISMATCH` | 4 | 1 | unchanged |
| 5 | PATCH `{"officeCode":…}` | `400 FIELD_NOT_OWNED`, `fields: ["officeCode"]` | 4 | 1 | unchanged |
| 6 | PATCH `{"specialAssistance":null}` | `200`: cleared; pickup kept | 4 | 2 | Gate 2, Tech Park |
| 7 | HR v5 moves the employee | `APPLIED`; pickup **kept** | 5 | 2 | Gate 2, Tech Park |

**The requests.** `PATCH http://localhost:8080/api/v1/employees/E1001/transport`, role `ADMIN`:
```bash
send $HR "$(ev UPDATE E1001 4 HYD-02 NIGHT ',"pickupPoint":"Gate 9"')"
curl -s $BASE/actuator/prometheus | grep '^hr_owned_field_violation_total'
curl -s -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 2"}'; echo
curl -s -i -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "0"' \
  -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 2, Tech Park","specialAssistance":"wheelchair"}' | grep -iE '^(HTTP|etag)'
curl -s -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "0"' -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 5"}'; echo
curl -s -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "1"' -H 'Content-Type: application/json' -d '{"officeCode":"BLR-01"}'; echo
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "1"' -H 'Content-Type: application/json' -d '{"specialAssistance":null}'
send $HR "$(ev UPDATE E1001 5 BLR-01 DAY)"
show E1001
```
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
hr_owned_field_violation_total{application="hr-roster-sync",field="pickupPoint",tenant="acme"} 1.0
{…,"status":428,"title":"Precondition Required","code":"PRECONDITION_REQUIRED","traceId":"…"}
HTTP/1.1 200
ETag: "1"
{…,"status":412,"title":"Precondition Failed","code":"TRANSPORT_VERSION_MISMATCH","traceId":"…"}
{…,"status":400,"code":"FIELD_NOT_OWNED","traceId":"…","fields":["officeCode"]}
200
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{'employeeId': 'E1001', 'hrVersion': 5, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': 'Gate 2, Tech Park', 'specialAssistance': None, 'transportVersion': 2}
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`TransportPatchIT.laterHrUpdatesKeepTheTransportFields`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java#L129) | an HR update after an admin edit keeps the pickup point | Integration |
| [`TransportPatchIT.staleIfMatchIs412AndChangesNothing`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java#L71) | an edit based on an old version is refused and changes nothing | Integration |
| [`TransportPatchIT.missingIfMatchIs428`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java#L81) | an edit without `If-Match` is refused | Integration |
| [`TransportPatchIT.hrOwnedFieldInTheBodyIsRefusedAndNothingChanges`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java#L96) | an admin can't touch an HR field | Integration |
| [`TransportPatchIT.absentFieldIsKeptAndNullClearsIt`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java#L113) | a field left out is kept; `null` clears it | Integration |
| [`IngestControllerIT.transportFieldsInAnHrPayloadAreIgnoredAndCounted`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L155) | a pickup point from HR is ignored and counted | Integration |
| [`ApplyServiceIT.hrCannotOverwriteTransportFields`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/ApplyServiceIT.java#L148) | HR changes never reach transport fields | Integration |
| [`EmployeeRepositoryIT.hrWritesNeverTouchTransportColumns`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java#L168) | checked at the database: HR's SQL can't write transport columns | Integration |
| [`EmployeeRepositoryIT.transportWritesDoNotMoveTheHrVersion`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java#L197) | the two versions are independent | Integration |
| [`FieldOwnershipPolicyTest.hrOwnsNameEmailManagerAndOfficePlusAssumedPhoneShiftAndStatus`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicyTest.java#L21) | the field → owner table | Unit |

Code: [`EmployeeField`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/EmployeeField.java#L14) (the ownership table) · [`JdbcEmployeeRepository.updateHrFields`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcEmployeeRepository.java#L79) · [`JdbcEmployeeRepository.updateTransportFields`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcEmployeeRepository.java#L93) · [`EmployeeController.patchTransport`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/api/employee/EmployeeController.java#L69).

---

## 5. Comparing 200,000 employees every night without running out of memory

**The rule.** The nightly file is compared with the database in two passes over 64 hash buckets, one bucket in memory at a time. The result is a plan; only real differences are applied, through the same rules as live changes.

**The data.** The file:
```
#HEADER,tenant=acme,as_of=2026-10-31T10:00:31Z
employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status
E1001,1,Asha,Rao,e1001@acme.example,+919876543210,,BLR-01,DAY,ACTIVE
E1002,1,Asha,Rao,e1002@acme.example,+919876543210,,BLR-01,DAY,ACTIVE
N0001,1,Asha,Rao,n0001@acme.example,+919876543210,,BLR-01,DAY,ACTIVE
… N0002 to N0010 …
#TRAILER,count=12
```

| File row vs stored employee | Plan (`reconciliation_item.action`) | In this file |
|---|---|---|
| not stored | `CREATE` | N0001–N0010 → 10 creates |
| newer version, different content | `UPDATE` | — |
| same version, same content | nothing | E1002 (v1, unchanged) |
| older version | nothing (the live feed is ahead) | E1001 (file v1, stored v5) |
| stored `ACTIVE`, missing from the file, unchanged since `as_of` | `DEACTIVATE` | — |
| an invalid line | nothing; the employee counts as present | — |

Each employee's bucket (`employee.bucket`, 0–63) is `murmur3(employee_id) mod 64`, the same function on both sides. The run itself is one `reconciliation_run` row with all the counts.

**The request.** `POST http://localhost:8080/api/v1/reconciliations` (multipart `file`, role `HR_INTEGRATION`) → `202 {"runId": …}`; then `GET http://localhost:8080/api/v1/reconciliations/{runId}` (role `OPERATOR`):
```bash
night /tmp/night1.csv yes E1001 E1002 N0001 N0002 N0003 N0004 N0005 N0006 N0007 N0008 N0009 N0010
RUN=$(upload /tmp/night1.csv); runinfo $RUN
```
```
{'status': 'COMPLETED', 'activeBefore': 2, 'toCreate': 10, 'toUpdate': 0, 'toDeactivate': 0, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': None, 'applied': 10, 'skipped': 0}
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`ReconciliationBenchmarkIT.fullNightFitsTheBudget`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ReconciliationBenchmarkIT.java#L55) | 200,000 stored vs a 201,000-row file on a **256 MB heap**: plan 3.0 s, plan + apply 24.9 s, peak 127 MB (`./mvnw verify -Pbenchmark`) | Benchmark |
| [`ReconciliationPlannerIT.plansOnlyTheChangesAndRecordsBadLinesByNumberAndCode`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlannerIT.java#L66) | only real changes are planned; bad lines by number and code | Integration |
| [`ReconciliationPlannerIT.invalidRowNeverDeactivatesItsEmployee`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlannerIT.java#L98) | a broken line counts as present | Integration |
| [`ReconciliationPlannerIT.workingFilesAreDeletedAfterPlanning`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlannerIT.java#L142) | the 64 temporary files are always deleted | Integration |
| [`ReconciliationIT.normalNightAppliesThePlan`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java#L96) | a normal night applies its plan | Integration |
| [`ReconciliationIT.approvalLaterStillRespectsLiveChangesMadeInBetween`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java#L147) | a live change made after the file was taken is never undone | Integration |
| [`ReconciliationDifferTest.missingEmployeeChangedAfterTheSnapshotIsLeftAlone`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/reconciliation/ReconciliationDifferTest.java#L85) | one row of the comparison table (all 13 are in this class) | Unit |
| [`EmployeeRepositoryIT.bucketColumnUsesTheSameFunctionAsTheNightlyDiff`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java#L82) | stored buckets match the planner's bucket function | Integration |

Code: [`ReconciliationPlanner.plan`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlanner.java#L86) · [`ReconciliationDiffer.diff`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/reconciliation/ReconciliationDiffer.java#L32) · [`BucketAssigner.bucketOf`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/reconciliation/BucketAssigner.java#L26).

---

## 6. A broken nightly file must not deactivate thousands of people

**The rule.** Before anything is applied, the safety brake checks the finished plan. A missing or wrong trailer (`FILE_TRUNCATED`), or deactivations ≥ 30 % of active employees (`MISSING_RATIO_EXCEEDED`), holds the **whole** plan for an operator.

**The data.** acme has 12 active employees after problem 5.

| File | Rows | Trailer | `to_deactivate` / `active_before` | `brake_reason` | `status` |
|---|---|---|---|---|---|
| `cut.csv` | E1001, E1002, N0001–N0003, then a half line `N0011,1,Asha,Ra` | missing | 7 / 12 | `FILE_TRUNCATED` | `AWAITING_APPROVAL` → operator rejects → `REJECTED` |
| `half.csv` | E1001, E1002, N0001–N0005 | `count=7` | 5 / 12 = 41.7 % | `MISSING_RATIO_EXCEEDED` | `AWAITING_APPROVAL` → operator approves → `COMPLETED`, 5 applied |

`reconciliation_error` for `cut.csv` holds one row: `line_no 8, employee_id null, error_code MALFORMED_RECORD`. It stores no content from the line.

**The requests.** `POST http://localhost:8080/api/v1/reconciliations/{runId}/reject` and `…/approve`, role `OPERATOR`:
```bash
night /tmp/cut.csv no E1001 E1002 N0001 N0002 N0003
CUT=$(upload /tmp/cut.csv); runinfo $CUT
curl -s $BASE/api/v1/reconciliations/$CUT/errors -H "Authorization: Bearer $OPS"; echo
curl -s -X POST $BASE/api/v1/reconciliations/$CUT/reject  -H "Authorization: Bearer $OPS" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])'
curl -s -X POST $BASE/api/v1/reconciliations/$CUT/approve -H "Authorization: Bearer $OPS"; echo

night /tmp/half.csv yes E1001 E1002 N0001 N0002 N0003 N0004 N0005
HALF=$(upload /tmp/half.csv); runinfo $HALF
curl -s -o /dev/null -w 'approve -> %{http_code}\n' -X POST $BASE/api/v1/reconciliations/$HALF/approve -H "Authorization: Bearer $OPS"
runinfo $HALF
```
```
{'status': 'AWAITING_APPROVAL', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 7, 'rowsInvalid': 1, 'trailerOk': False, 'brakeReason': 'FILE_TRUNCATED', 'applied': 0, 'skipped': 0}
[{"lineNo":8,"employeeId":null,"code":"MALFORMED_RECORD"}]
REJECTED
{…,"status":409,"title":"Conflict","code":"RUN_NOT_AWAITING_APPROVAL","traceId":"…"}
{'status': 'AWAITING_APPROVAL', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 5, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': 'MISSING_RATIO_EXCEEDED', 'applied': 0, 'skipped': 0}
approve -> 202
{'status': 'COMPLETED', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 5, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': 'MISSING_RATIO_EXCEEDED', 'applied': 5, 'skipped': 0}
```
At full size (200,000 employees, file cut at row 120,000): 79,999 deactivations held, nobody touched. See the testing guide, [Module 12](testing-guide.md#module-12-the-case-study-at-full-size-200000-employees).

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`ExampleUseCaseIT.truncatedFileAt120kDeactivatesNobody`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java#L78) | the case study's exact example: 200,000 employees, file cut mid-line at 120,000 → held, 79,999 planned, nobody deactivated | Integration |
| [`ReconciliationIT.brakeStopsRun`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java#L117) | ≥ 30 % missing holds the run | Integration |
| [`ReconciliationIT.fileWithoutTrailerTripsEvenWhenNobodyIsMissing`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java#L138) | a cut file is never trusted, even with nothing to deactivate | Integration |
| [`ReconciliationApiIT.trippedRunWaitsAndAnOperatorApprovesIt`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java#L97) | the approve flow over HTTP | Integration |
| [`ReconciliationApiIT.rejectedRunChangesNothingAndCannotBeDecidedTwice`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java#L110) | reject changes nothing; a second decision is `409` | Integration |
| [`ReconciliationApiIT.exportForAnotherTenantIsRefusedBeforeAnyRunExists`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java#L135) | a file for another client is refused at once | Integration |
| [`SafetyBrakeTest.tripsAtOrAboveThirtyPercentOfActiveEmployees`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrakeTest.java#L53) | exactly 30 % trips; just under doesn't (exact decimal arithmetic) | Unit |
| [`BrakeThresholdConfigIT`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/BrakeThresholdConfigIT.java#L27) | the threshold comes from configuration | Integration |
| [`ops/prometheus/alerts.test.yml`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/ops/prometheus/alerts.test.yml) | the `ReconciliationBrakeTripped` alert fires on a client's first trip (`scripts/check-alerts.sh`) | Alert rule test |

Code: [`SafetyBrake.evaluate`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrake.java#L22) · [`ReconciliationService.execute`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationService.java#L47) · [`JdbcReconciliationStore.recordDecision`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcReconciliationStore.java#L121) (decide once).

---

## 7. Every change must reach downstream systems, even across a crash

**The rule.** The change and its event are saved in one transaction (the outbox). A relay publishes events in order and marks them only after the broker confirms. Consumers ignore duplicates by event id.

**The data.**

| Moment | `employee` E1002 | `outbox` row for that change | metric `outbox_depth` |
|---|---|---|---|
| broker stopped, HR sends v2 (office → HYD-02) | v2, `HYD-02` (committed) | `published_at` null, `attempts` growing, `last_error_code` set | 1 |
| broker started again | unchanged | `published_at` set | 0 |

**The request.** `GET http://localhost:8080/actuator/prometheus`:
```bash
docker compose stop redpanda
send $HR "$(ev UPDATE E1002 2 HYD-02 DAY)"
sleep 8; curl -s $BASE/actuator/prometheus | grep -E '^outbox_(depth|oldest)'
docker compose start redpanda
sleep 20; curl -s $BASE/actuator/prometheus | grep -E '^outbox_(depth|oldest)'
```
```
{"results":[{"index":0,"employeeId":"E1002","outcome":"APPLIED"}]}
outbox_depth{application="hr-roster-sync"} 1.0
outbox_oldest_age_seconds{application="hr-roster-sync"} 8.08888
outbox_depth{application="hr-roster-sync"} 0.0
outbox_oldest_age_seconds{application="hr-roster-sync"} 0.0
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`OutboxCrashRecoveryIT.crashAfterCommitBeforePublishIsDeliveredAfterRestart`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/OutboxCrashRecoveryIT.java#L122) | crash after saving, before publishing → delivered after restart | Integration (failure injection) |
| [`OutboxCrashRecoveryIT.crashAfterPublishBeforeMarkingResendsAndTheConsumerActsOnce`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/OutboxCrashRecoveryIT.java#L140) | crash after publishing, before marking → sent twice, acted on once | Integration (failure injection) |
| [`BrokerDownIT.outboxBacksUpWhileTheBrokerIsDownAndDrainsInOrderAfterwards`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/messaging/BrokerDownIT.java#L88) | a broker outage: ingest continues, the backlog drains in order | Integration (failure injection) |
| [`OutboxIT.eventWrittenInSameTransaction`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/OutboxIT.java#L126) | if the event can't be written, the change isn't saved either | Integration |
| [`OutboxIT.appliedChangeWritesOneUnpublishedEventWithIdsAndFieldNamesOnly`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/OutboxIT.java#L68) | one event per change; ids and field names, no values | Integration |
| [`OutboxRelayIT.stopsAtTheFirstFailureAndKeepsLaterEventsForTheNextRun`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/OutboxRelayIT.java#L114) | a failed send blocks later events, so nothing overtakes it | Integration |
| [`OutboxRelayIT.differentEmployeesAreInFlightTogetherButOneEmployeeNeverHasTwo`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/OutboxRelayIT.java#L190) | fast pipelined sending without reordering one employee | Integration |
| [`OutboxRelayIT.onlyOneRelayIsActiveAtATime`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/OutboxRelayIT.java#L223) | two app instances never publish at once | Integration |

Code: [`OutboxRelay.relayOnce`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/application/OutboxRelay.java#L59) · [`JdbcOutbox.enqueue`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcOutbox.java#L22) · [`JdbcPendingOutbox.lockNextBatch`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcPendingOutbox.java#L30).

---

## 8. People who leave must lose their cab bookings

**The rule.** An `EMPLOYEE_DEACTIVATED` event cancels the person's bookings on trips that haven't left yet and frees the seats, once, in one transaction. Past trips stay as history.

**The data.**

| Table | Before the exit | After the exit |
|---|---|---|
| `employee` E1001 | v5, `ACTIVE` | v6, `INACTIVE` |
| `booking` (E1001, trip `T-ACME-1`, tomorrow) | `BOOKED` | `CANCELLED`, `cancel_reason = EMPLOYEE_DEACTIVATED` |
| `trip` `T-ACME-1` | `seats_booked` 1 | `seats_booked` 0 |
| `processed_message` | — | `(booking-cancellation, <event id>)` |
| topic `rostersync.employee-changes` | | key `acme:E1001`, `{"type": "EMPLOYEE_DEACTIVATED", "hrVersion": 6, "changedFields": ["status"], …}` |

**The requests.** `POST http://localhost:8080/api/v1/bookings` and `GET http://localhost:8080/api/v1/bookings?employeeId=E1001`, role `ADMIN`:
```bash
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-1","employeeId":"E1001"}'; echo
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-OLD","employeeId":"E1002"}'; echo
send $HR "$(ev DEACTIVATE E1001 6 BLR-01 DAY)"
sleep 2; curl -s "$BASE/api/v1/bookings?employeeId=E1001" -H "Authorization: Bearer $ADMIN"; echo
docker compose exec -T redpanda rpk topic consume rostersync.employee-changes -o :end -f '%k %v\n' | grep '^acme:E1001' | tail -1
```
```
{"id":"…","tripId":"T-ACME-1","employeeId":"E1001","status":"BOOKED","cancelReason":null,"pickupTime":"…"}
{…,"status":409,"title":"Conflict","code":"TRIP_DEPARTED","traceId":"…"}
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
[{"id":"…","tripId":"T-ACME-1","employeeId":"E1001","status":"CANCELLED","cancelReason":"EMPLOYEE_DEACTIVATED","pickupTime":"…"}]
acme:E1001 {"type": "EMPLOYEE_DEACTIVATED", "status": "INACTIVE", "eventId": "…", "tenantId": "acme", "hrVersion": 6, "employeeId": "E1001", "occurredAt": "2026-10-30T08:00:00Z", "changedFields": ["status"], "transportVersion": 2}
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`DeactivationConsumerIT.deactivationCancelsFutureBookingsAndFreesSeats`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java#L90) | future bookings cancelled, past kept, seats exact | Integration |
| [`DeactivationConsumerIT.redeliveredDeactivationChangesNothing`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java#L109) | the same event twice cancels once | Integration |
| [`DeactivationConsumerIT.lateCopyOfAnOldExitDoesNotCancelBookingsMadeAfterRehire`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java#L126) | a late duplicate of an old exit can't cancel a rehired person's new booking; fails without the event-id check | Integration |
| [`DeactivationConsumerIT.otherChangesCancelNothing`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java#L144) | only exits cancel bookings | Integration |
| [`BookingApiIT.anExitSentByHrCancelsTheBookingEndToEnd`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/booking/BookingApiIT.java#L113) | API → outbox → broker → consumer, end to end | Integration |
| [`BookingApiIT.fullDepartedAndDuplicateBookingsAreRefused`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/booking/BookingApiIT.java#L82) | booking rules: full, departed, duplicate | Integration |

Code: [`DeactivationHandler.handle`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/application/DeactivationHandler.java#L36) · [`JdbcBookings.cancelFutureBookings`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcBookings.java#L25) (cancel and free seats in one SQL statement) · [`JdbcProcessedMessages.markProcessed`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcProcessedMessages.java#L18).

---

## 9. Personal data must never leak

**The rule.** Logs, metrics, errors and events carry ids, codes and field *names*, never field values.

**The data.** What each output may contain:

| Output | Contains | Never contains |
|---|---|---|
| API error body | `code`, `traceId`, field names (`fields: ["officeCode"]`) | the rejected value |
| Log line (JSON) | `traceId`, `tenantId`, `employeeId`, version, outcome, code | name, email, phone, pickup point, special assistance |
| Metric labels | `tenant`, `outcome`, `code`, `field` (a field *name*) | employee ids, any value |
| Change event | ids, versions, status, `changedFields` names | any field value |
| `reconciliation_error` | `line_no`, `employee_id` (when the line is readable), `error_code` | the line's content |

**The request.** `POST http://localhost:8080/api/v1/hr/events:batch` with a distinctive name and phone, valid and invalid, then search everything:
```bash
send $HR "$(ev CREATE E3001 1 BLR-01 DAY ',"firstName":"Zephyrine","phone":"+919812345678","email":"zephyrine.q@acme.example"')"
send $HR "$(ev CREATE E3002 1 BLR-01 DAY ',"firstName":"Zephyrine","phone":"12ab"')"
docker compose logs app | grep -ciE 'zephyrine|9812345678'
curl -s $BASE/actuator/prometheus | grep -ciE 'zephyrine|9812345678'
```
```
{"results":[{"index":0,"employeeId":"E3001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E3002","outcome":"REJECTED","errorCode":"INVALID_PHONE"}]}
0
0
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`NoPiiInLogsIT.noPersonalValueReachesLogsMetricsOrErrorBodies`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/NoPiiInLogsIT.java#L93) | distinctive values through every flow (live, nightly, broker, valid, invalid): absent from all output, metrics and error bodies; found and fixed one real leak | Integration |
| [`IngestControllerIT.errorReportContainsNoPersonalData`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java#L141) | the batch report never echoes values | Integration |
| [`GlobalExceptionHandlerTest.unexpectedErrorIs500WithoutTheExceptionMessage`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/GlobalExceptionHandlerTest.java#L73) | an exception's message never reaches a response | Unit |
| [`CompositeValidatorTest.errorsAndRecordToStringNeverContainTheRejectedValues`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java#L210) | validation errors and record printouts carry no values | Unit |
| [`ReconciliationPlannerIT.aLineWithTheWrongColumnCountIsReportedWithoutItsFirstFieldButStillCountsAsPresent`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlannerIT.java#L111) | a garbled file line (which may start with a name) is reported by number only | Integration |
| [`OutboxIT.transportEditWritesATransportEventWithoutTheAddress`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/OutboxIT.java#L112) | the pickup address never enters an event | Integration |

Code: [`DomainException`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/domain/DomainException.java#L7) (message = code) · [`GlobalExceptionHandler.unexpected`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/api/GlobalExceptionHandler.java#L96) (logs class and location, never the message).

---

## 10. One client never sees another's data; each person only what their role allows

**The rule.** The client (tenant) comes only from the signed token. Another client's record answers `404`, exactly like a missing one. Every endpoint has an explicit role; anything else is denied.

**The data.** The same employee id in two clients is two rows:

| `tenant_id` | `employee_id` | `office_code` | Visible to |
|---|---|---|---|
| `acme` | `E1001` | `BLR-01` | acme tokens only |
| `globex` | `E1001` | `CHN-01` | globex tokens only |

| Role (token claim) | May call |
|---|---|
| `HR_INTEGRATION` | `POST /api/v1/hr/events:batch`, `POST /api/v1/reconciliations` |
| `ADMIN` | `GET /api/v1/employees/{id}`, `PATCH …/transport`, bookings |
| `OPERATOR` | reconciliation runs: upload, read, approve, reject |
| `EMPLOYEE` | `GET /api/v1/employees/me` (id taken from the token) |

**The requests.** `GET http://localhost:8080/api/v1/employees/{id}`:
```bash
curl -s -o /dev/null -w 'globex admin, E1001 -> %{http_code}\n' $BASE/api/v1/employees/E1001 -H "Authorization: Bearer $GLOBEX_ADMIN"
curl -s -o /dev/null -w 'acme admin, E9999 -> %{http_code}\n'   $BASE/api/v1/employees/E9999 -H "Authorization: Bearer $ADMIN"
send $GLOBEX_HR "$(ev CREATE E1001 1 CHN-01 DAY)"     # globex's own E1001
send $GLOBEX_HR "$(ev CREATE E2001 1 HYD-02 DAY)"     # acme's office, unknown to globex
ME=$(tok acme E1002 EMPLOYEE)
curl -s $BASE/api/v1/employees/me -H "Authorization: Bearer $ME" | python3 -c 'import sys,json; print(json.load(sys.stdin)["employeeId"])'
curl -s -o /dev/null -w 'employee reads E1001 -> %{http_code}\n' $BASE/api/v1/employees/E1001 -H "Authorization: Bearer $ME"
curl -s -o /dev/null -w 'admin pushes HR data -> %{http_code}\n' -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}'
curl -s -o /dev/null -w 'no token -> %{http_code}\n' $BASE/api/v1/employees/E1001
```
```
globex admin, E1001 -> 404
acme admin, E9999 -> 404
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E2001","outcome":"REJECTED","errorCode":"UNKNOWN_OFFICE"}]}
E1002
employee reads E1001 -> 403
admin pushes HR data -> 403
no token -> 401
```

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`TenantIsolationIT.anotherTenantsEmployeeLooksExactlyLikeAMissingOne`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java#L61) | the 404 body is identical to a missing id's | Integration |
| [`TenantIsolationIT.anotherTenantsEmployeeCannotBeEdited`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java#L73) | no edit across clients | Integration |
| [`TenantIsolationIT.hrFeedOfOneTenantNeverTouchesAnotherTenantsEmployee`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java#L92) | the same id in two clients stays two employees | Integration |
| [`TenantIsolationIT.anotherTenantsOfficeIsUnknown`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java#L106) | offices are per client | Integration |
| [`SchemaIT.anotherTenantsOfficeCannotBeUsed`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java#L81) | the database refuses a cross-client office reference | Integration |
| [`SecurityIT.tokenSignedWithAnotherKeyIs401`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java#L60) | a forged token is refused | Integration |
| [`SecurityIT.onlyTheHrIntegrationRoleMayIngest`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java#L78) | role checks on ingest | Integration |
| [`SecurityIT.anUnlistedEndpointIsDeniedEvenWithAValidToken`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java#L96) | deny by default | Integration |
| [`ReconciliationApiIT.rolesAreEnforced`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java#L194) | role checks on nightly runs | Integration |
| [`DevTokenAbsentIT.devTokenEndpointDoesNotExistOutsideDevAndDemo`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/security/DevTokenAbsentIT.java#L17) | the demo token endpoint doesn't exist in production | Integration |

Code: [`TenantArgumentResolver.resolveArgument`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/api/security/TenantArgumentResolver.java#L25) (tenant from the token) · [`SecurityConfig.apiSecurity`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/api/security/SecurityConfig.java#L34) (who may call what).

---

## 11. On-call must see trouble at 3 AM

**The rule.** The numbers on-call needs are exposed as metrics; five alert rules page a person; logs are JSON lines tied together by a trace id.

**The data.** The main metrics (all labelled `application="hr-roster-sync"`):

| Metric | Labels | Meaning | Alert |
|---|---|---|---|
| `hr_events_total` | `tenant`, `outcome`, `code` | records by outcome and rejection code | `HrRejectionSpike` |
| `hr_ingest_lag_seconds` | `tenant` | time from HR's change to applied (histogram) | `HrIngestLagHigh` |
| `outbox_depth`, `outbox_oldest_age_seconds` | — | unsent events; age of the oldest | `OutboxNotDraining` (> 60 s) |
| `reconciliation_brake_tripped_total` | `tenant`, `reason` | nightly runs held by the brake | `ReconciliationBrakeTripped` |
| `hr_owned_field_violation_total` | `tenant`, `field` | transport fields sent by HR | — |
| `cache_gets_total` | `cache`, `result` | cache hits and misses | — |

**The requests.** Your own trace id through to the log; then `GET http://localhost:8080/actuator/prometheus` and `GET http://localhost:9090/api/v1/alerts`:
```bash
curl -s -D - -o /dev/null -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: reviewer-trace-0001' -d "{\"sourceSystem\":\"workday\",\"events\":[$(ev UPDATE E1002 3 BLR-01 DAY ',"email":"bad"')]}" | grep -i '^x-trace-id'
sleep 1; docker compose logs app --no-log-prefix | grep reviewer-trace-0001
curl -s localhost:9090/api/v1/alerts | python3 -c 'import sys,json; [print(a["labels"]["alertname"], a["state"], a["labels"].get("reason","")) for a in json.load(sys.stdin)["data"]["alerts"]]'
```
```
X-Trace-Id: reviewer-trace-0001
{"@timestamp":"…","@version":"1","message":"hr event rejected version=3 code=INVALID_EMAIL","logger_name":"com.moveinsync.rostersync.application.ApplyService","thread_name":"…","level":"INFO","level_value":20000,"traceId":"reviewer-trace-0001","tenantId":"acme","employeeId":"E1002"}
ReconciliationBrakeTripped firing FILE_TRUNCATED
ReconciliationBrakeTripped firing MISSING_RATIO_EXCEEDED
```
The brake alert fires within one evaluation (about 15 s) of a trip and resolves 15 minutes after the last one, so check it within 15 minutes of problem 6. Dashboard: http://localhost:3000. Alert rules: http://localhost:9090/alerts.

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`MetricsIT.everyMonitoringSeriesIsExposed`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/MetricsIT.java#L85) | after real traffic, every series above is on `/actuator/prometheus` | Integration |
| [`MetricsIT.otherActuatorEndpointsAreNotExposed`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/MetricsIT.java#L78) | only `health` and `prometheus` are open | Integration |
| [`StructuredLoggingIT.logLinesAreJsonWithTheLoggingContextAsFields`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/StructuredLoggingIT.java#L21) | logs are JSON with trace, client and employee ids | Integration |
| [`GlobalExceptionHandlerTest.traceIdInTheBodyMatchesTheResponseHeader`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/GlobalExceptionHandlerTest.java#L66) | the same trace id in the header and the error body | Unit |
| [`ops/prometheus/alerts.test.yml`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/ops/prometheus/alerts.test.yml) | every alert fires and resolves as specified, including "a quiet hour is not a spike" (`scripts/check-alerts.sh`) | Alert rule test |

Code: [`ops/prometheus/alerts.yml`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/ops/prometheus/alerts.yml) · [`OutboxMetrics`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/OutboxMetrics.java#L15) · [`TraceIdFilter`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/api/TraceIdFilter.java#L24) · dashboard [`ops/grafana/dashboards/hr-roster-sync.json`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/ops/grafana/dashboards/hr-roster-sync.json).

---

## 12. It must stay fast, and reads must not hammer the database

**The rule.** Two small in-memory caches serve repeated reads. Writes and version checks never use a cache, so a stale cache can show old data briefly but can never cause a wrong write.

**The data.**

| Cache (metric `cache=`) | Holds | Lifetime | Never cached | Dropped |
|---|---|---|---|---|
| `offices` | "office X is active for client Y" | 5 min | "unknown office" (so a new office works at once) | on expiry |
| `employees` | the admin's read view of one employee | 30 s | "not found" | after every change this server makes |

**The requests.** `GET http://localhost:8080/api/v1/employees/E1002` twice, then the cache counters; then the load test:
```bash
show E1002; show E1002
curl -s $BASE/actuator/prometheus | grep '^cache_gets_total' | sed 's/application="hr-roster-sync",//'
scripts/loadtest.sh      # k6 in Docker: 20 virtual users, 60 s, every record a real change
```
```
{'employeeId': 'E1002', 'hrVersion': 2, 'officeCode': 'HYD-02', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
{'employeeId': 'E1002', 'hrVersion': 2, 'officeCode': 'HYD-02', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
cache_gets_total{cache="employees",result="hit"} 2.0
cache_gets_total{cache="employees",result="miss"} 6.0
cache_gets_total{cache="offices",result="hit"} 26.0
cache_gets_total{cache="offices",result="miss"} 5.0
```
The exact counts depend on what you ran before; each pair of reads adds one `employees` miss, then one hit. Office lookups are almost all hits: 26 of 31 here, after the batches and nightly files of problems 1–10. The load test passes when both thresholds show `✓` (p99 < 2 s, < 1 % failed). Measured on the test laptop: 5,858 records/s, p99 556 ms per 50-record batch (full results: README [Performance and cost](../README.md#performance-and-cost)).

**The tests.**

| Test | Proves | Kind |
|---|---|---|
| [`OfficeCacheTest.aKnownOfficeIsLookedUpOnceAndThenServedFromTheCache`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/OfficeCacheTest.java#L41) | one database lookup, then hits | Unit |
| [`OfficeCacheTest.unknownAnswersAreNotCachedSoANewOfficeIsAcceptedAtOnce`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/OfficeCacheTest.java#L66) | "unknown" is never cached | Unit |
| [`OfficeCacheTest.aDeactivatedOfficeIsStillAcceptedUntilTheEntryExpiresAfterFiveMinutes`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/infrastructure/persistence/OfficeCacheTest.java#L53) | the stated staleness, exactly 5 minutes (fake clock) | Unit |
| [`EmployeeReadCacheIT.anHrChangeIsVisibleOnTheNextRead`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/EmployeeReadCacheIT.java#L48) | this server's own change is visible at once | Integration |
| [`EmployeeReadCacheIT.aStaleETagFromTheCacheCannotOverwriteANewerTransportEdit`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/test/java/com/moveinsync/rostersync/api/employee/EmployeeReadCacheIT.java#L82) | a stale cached read can't cause a wrong write (`412`) | Integration |
| [`loadtest/ingest.js`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/loadtest/ingest.js) | throughput and p99 under load, with pass/fail thresholds (`scripts/loadtest.sh`) | Load test |

Code: [`CachingOfficeDirectory.isKnownOffice`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/infrastructure/persistence/CachingOfficeDirectory.java#L39) · [`EmployeeReadCache.get`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/application/EmployeeReadCache.java#L50) · [`EmployeeService.get`](https://github.com/Hemal2005/Hr_Roster_Sync/blob/main/src/main/java/com/moveinsync/rostersync/application/EmployeeService.java#L42).
