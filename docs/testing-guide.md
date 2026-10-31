# Testing guide

Verification of HR Roster Sync against the running system (Part 1) and with the automated test suite (Part 2). See also the [README](../README.md) and, for the same checks organised by problem with the data model and a GitHub link to every test, [tests by problem](problem-tests.md).

| Part | Contents | Time |
|---|---|---|
| [1. Verify every rule by hand](#part-1-verify-every-rule-by-hand) | 20 modules, each with commands and the output of a reference run | 30 min core, 60–90 min all |
| [2. The automated test suite](#part-2-the-automated-test-suite) | The 335 automated tests and what each test class proves | 2–5 min |

Commands run from the repository root. The stack is started with `docker compose up -d --build --wait` (README, [Run it in 5 minutes](../README.md#run-it-in-5-minutes)).

## Part 1: Verify every rule by hand

Twenty modules that exercise each rule in the [README](../README.md) against the running system. Each module lists the commands, the output of a reference run, the automated tests that cover the same rule, and the code that implements it.

- Outputs come from a run against a fresh stack. Ids, trace ids and timestamps differ between runs.
- Modules build on each other in order. The dependencies are listed in the table; the numbers shown assume a fresh stack and this order.
- Modules 0–11 take about 30 minutes; all twenty take 60–90 minutes.

### The modules

| # | Module | Verifies | Time | Needs |
|---|---|---|---|---|
| 0 | [Start fresh and load the helpers](#module-0-start-fresh-and-load-the-helpers) | Clean stack, shell helpers, access tokens | 5 min | Docker |
| 1 | [Is everything running?](#module-1-is-everything-running) | Five healthy services | 1 min | — |
| 2 | [Who may call what](#module-2-who-may-call-what) | 401, 403, deny by default | 3 min | — |
| 3 | [Bad data never blocks good data](#module-3-bad-data-never-blocks-good-data) | Per-record rejection with a code; request-level errors | 3 min | — |
| 4 | [The same change twice](#module-4-the-same-change-twice) | Duplicate vs conflict | 2 min | — |
| 5 | [Changes out of order](#module-5-changes-out-of-order) | The case study's v7-before-v6 example | 2 min | 4 |
| 6 | [HR vs the transport team](#module-6-hr-vs-the-transport-team) | Field ownership, `If-Match`, 412 / 428 | 5 min | 5 |
| 7 | [One client never sees another](#module-7-one-client-never-sees-another) | Identical 404s, separate data, per-client offices | 3 min | 6 |
| 8 | [Each person only what their role allows](#module-8-each-person-only-what-their-role-allows) | `/me`, role refusals | 2 min | 4 |
| 9 | [An exit cancels the cab booking](#module-9-an-exit-cancels-the-cab-booking) | Change events, booking consumer, topic contents | 5 min | 6 |
| 10 | [The nightly file: a normal night](#module-10-the-nightly-file-a-normal-night) | Plan, then apply only real changes | 3 min | 9 |
| 11 | [The safety brake](#module-11-the-safety-brake) | Cut-off file held; ≥ 30 % held; approve / reject | 5 min | 10 |
| 12 | [The case study at full size: 200,000 employees](#module-12-the-case-study-at-full-size-200000-employees) | File cut at row 120,000 of 200,000 | 5 min | JDK 21 |
| 13 | [What on-call sees](#module-13-what-on-call-sees) | Trace ids, JSON logs, metrics, alerts, dashboard | 5 min | 11 |
| 14 | [No personal data leaks](#module-14-no-personal-data-leaks) | Names and phone numbers absent from logs and metrics | 2 min | — |
| 15 | [Failure drills: broker down, app killed](#module-15-failure-drills-broker-down-app-killed) | No lost or doubled changes | 5 min | 13 |
| 16 | [Look at the data directly](#module-16-look-at-the-data-directly) | Database-level guarantees | 3 min | — |
| 17 | [Load test](#module-17-load-test) | Throughput, p99, pass/fail thresholds | 2 min | — |
| 18 | [The automated test suite](#module-18-the-automated-test-suite) | 177 unit + 158 integration tests, benchmark, alert tests | 5–10 min | JDK 21 |
| 19 | [Prove the tests can fail](#module-19-prove-the-tests-can-fail) | Each guarded rule, broken on purpose | 5 min | JDK 21 |

### Module 0: Start fresh and load the helpers

A fresh stack, so the outputs match this guide:
```bash
git clone https://github.com/Hemal2005/Hr_Roster_Sync.git   # skip if already cloned
cd Hr_Roster_Sync                                            # keep this folder name (see Module 17)
docker compose down -v                                       # removes any earlier data; harmless on a first run
docker compose up -d --build --wait                          # 3–5 min the first time, seconds afterwards
bash                                                         # the commands below are tested in bash (macOS defaults to zsh)
```

Shell helpers and access tokens (JWT) for the demo identities. The helpers are defined per shell session, and the access tokens are valid for 8 hours; a `401` on a later module means the block needs to be run again.

```bash
BASE=http://localhost:8080

# tok TENANT SUBJECT ROLE: a demo token (the demo profile only; see "Demo data and identities")
tok() { curl -s -X POST $BASE/dev/token -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$1\",\"subject\":\"$2\",\"roles\":[\"$3\"]}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'; }

# ev TYPE ID VERSION OFFICE SHIFT [extra JSON]: one HR event carrying the employee's full HR state
ev() { printf '{"type":"%s","employeeId":"%s","version":%s,"occurredAt":"2026-10-30T08:00:00Z","firstName":"Asha","lastName":"Rao","email":"%s@acme.example","phone":"+919876543210","officeCode":"%s","shiftCode":"%s"%s}' \
  "$1" "$2" "$3" "$(echo "$2" | tr A-Z a-z)" "$4" "$5" "${6:-}"; }

# send TOKEN EVENT...: post one batch of HR events
send() { local t=$1; shift; local IFS=,; curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $t" \
  -H 'Content-Type: application/json' -d "{\"sourceSystem\":\"workday\",\"events\":[$*]}"; echo; }

# show ID: the admin's view of one acme employee, the fields that matter
show() { curl -s $BASE/api/v1/employees/$1 -H "Authorization: Bearer $ADMIN" | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["employeeId","hrVersion","officeCode","shiftCode","status","pickupPoint","specialAssistance","transportVersion"]})'; }

# night FILE TRAILER(yes|no) ID...: an acme nightly file, one ACTIVE row per id at version 1
night() { local f=$1 t=$2; shift 2; { echo "#HEADER,tenant=acme,as_of=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status"
  for id in "$@"; do echo "$id,1,Asha,Rao,$(echo "$id" | tr A-Z a-z)@acme.example,+919876543210,,BLR-01,DAY,ACTIVE"; done
  if [ "$t" = yes ]; then echo "#TRAILER,count=$#"; else printf 'N0011,1,Asha,Ra'; fi; } > "$f"; }

# upload FILE: send a nightly file, print the run id.  runinfo RUN: the run's key numbers, after it settles
upload() { curl -s -X POST $BASE/api/v1/reconciliations -H "Authorization: Bearer $HR" -F "file=@$1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["runId"])'; }
runinfo() { sleep 3; curl -s $BASE/api/v1/reconciliations/$1 -H "Authorization: Bearer $OPS" | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["status","activeBefore","toCreate","toUpdate","toDeactivate","rowsInvalid","trailerOk","brakeReason","applied","skipped"]})'; }

HR=$(tok acme workday HR_INTEGRATION); ADMIN=$(tok acme admin-1 ADMIN); OPS=$(tok acme ops-1 OPERATOR)
GLOBEX_HR=$(tok globex workday HR_INTEGRATION); GLOBEX_ADMIN=$(tok globex admin-1 ADMIN)
echo "ready: ${#HR}-character token"
```

Output: `ready: 195-character token`. A Python `KeyError` or `JSONDecodeError` here means the app is still starting; `docker compose ps` shows when `app` is `healthy`.

| Variable | Client | Identity | Role |
|---|---|---|---|
| `HR` | acme | the HR system | `HR_INTEGRATION` |
| `ADMIN` | acme | transport admin | `ADMIN` |
| `OPS` | acme | operator for nightly runs | `OPERATOR` |
| `GLOBEX_HR`, `GLOBEX_ADMIN` | globex | a second client | `HR_INTEGRATION`, `ADMIN` |

### Module 1: Is everything running?

The five services are up and the app reports ready.
```bash
docker compose ps --format '{{.Service}} {{.Status}}'
curl -s $BASE/actuator/health; echo
curl -s $BASE/actuator/health/readiness; echo
```
Output:
```
app Up 14 seconds (healthy)
grafana Up 9 seconds
postgres Up 19 seconds (healthy)
prometheus Up 19 seconds (healthy)
redpanda Up 19 seconds (healthy)
{"groups":["liveness","readiness"],"status":"UP"}
{"status":"UP"}
```
Readiness turns `UP` only after the database migrations have run.

Tests: [`ApplicationContextIT`](../src/test/java/com/moveinsync/rostersync/ApplicationContextIT.java), [`MetricsIT`](../src/test/java/com/moveinsync/rostersync/MetricsIT.java). Code: [`docker-compose.yml`](../docker-compose.yml), [`Dockerfile`](../Dockerfile).

### Module 2: Who may call what

Requests without a valid access token get 401, requests with the wrong role get 403, and any endpoint not explicitly allowed is closed.
```bash
# no token at all
curl -s -i -X POST $BASE/api/v1/hr/events:batch -H 'Content-Type: application/json' -d '{}' | grep -iE '^(HTTP|www-authenticate)|code'
# a forged or garbage token
curl -s -X POST $BASE/api/v1/hr/events:batch -H 'Authorization: Bearer not.a.token' -H 'Content-Type: application/json' -d '{}'; echo
# a valid token with the wrong role: the transport admin tries to push HR data
curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{}'; echo
# a path nobody listed, even with a valid token
curl -s -o /dev/null -w '%{http_code}\n' $BASE/api/v1/anything -H "Authorization: Bearer $ADMIN"
# a dangerous actuator endpoint (configuration dump)
curl -s -o /dev/null -w '%{http_code}\n' $BASE/actuator/env
# asking the demo token endpoint for a role that doesn't exist
curl -s -o /dev/null -w '%{http_code}\n' -X POST $BASE/dev/token -H 'Content-Type: application/json' -d '{"tenantId":"acme","subject":"x","roles":["SUPERUSER"]}'
```
Output:
```
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8080/.well-known/oauth-protected-resource"
{"status":401,"title":"Unauthorized","type":"urn:rostersync:error:unauthenticated","code":"UNAUTHENTICATED","traceId":"…"}
{"status":401,"title":"Unauthorized","type":"urn:rostersync:error:unauthenticated","code":"UNAUTHENTICATED","traceId":"…"}
{"status":403,"title":"Forbidden","type":"urn:rostersync:error:forbidden","code":"FORBIDDEN","traceId":"…"}
403
401
400
```
- Every refusal uses the same problem format, with a `code` and a `traceId`.
- The unlisted path returns 403 rather than 404, so a newly added endpoint stays closed until a rule names a role for it.
- Of the actuator endpoints, only `health` and `prometheus` are open.

Tests: [`SecurityIT`](../src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java), [`DevTokenIT`](../src/test/java/com/moveinsync/rostersync/api/security/DevTokenIT.java), [`DevTokenAbsentIT`](../src/test/java/com/moveinsync/rostersync/api/security/DevTokenAbsentIT.java) (the token endpoint does not exist outside the demo profile). Code: [`SecurityConfig.apiSecurity`](../src/main/java/com/moveinsync/rostersync/api/security/SecurityConfig.java), [`ProblemResponses`](../src/main/java/com/moveinsync/rostersync/api/security/ProblemResponses.java).

### Module 3: Bad data never blocks good data

One batch with one valid record and six invalid ones, each a different kind of error; then the three request-level errors.
```bash
send $HR "$(ev CREATE E1002 1 BLR-01 DAY)" \
         "$(ev CREATE E1003 1 BLR-01 DAY ',"email":"not-an-email"')" \
         "$(ev CREATE E1004 1 BLR-01 DAY ',"phone":"98765"')" \
         "$(ev CREATE E1005 1 PUN-03 DAY)" \
         "$(ev CREATE E1006 1 BLR-01 DAY ',"version":"abc"')" \
         '{"type":"CREATE","employeeId":"E1007","version":1,"occurredAt":"2026-10-30T08:00:00Z"}' \
         "$(ev PROMOTE E1008 1 BLR-01 DAY)"
```
The records, in order: valid; malformed email; phone not in E.164; inactive office `PUN-03`; non-numeric version; no name, email, phone, office or shift; unknown event type.

Output (formatted):
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

Request-level errors:
```bash
# no sourceSystem, no events
curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d '{"events":[]}'; echo
# not JSON at all
curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d '{"sourceSystem":'; echo
# 1,001 events (the limit is 1,000)
python3 -c 'import json;print(json.dumps({"sourceSystem":"workday","events":[{}]*1001}))' > /tmp/big.json
curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d @/tmp/big.json; echo
```
Output:
```
{…,"status":400,"code":"INVALID_REQUEST","traceId":"…","fields":["events","sourceSystem"]}
{…,"status":400,"code":"MALFORMED_REQUEST","traceId":"…"}
{…,"status":400,"code":"INVALID_REQUEST","traceId":"…","fields":["events"]}
```
- The batch response is `200` with one result per record. Only an unusable request body fails as a whole.
- Index 4 has no `employeeId` because the record could not be parsed.
- Rejections carry a code and at most a field name; the rejected value (`not-an-email`) is not echoed.

Tests: [`IngestControllerIT`](../src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java) (one bad record in 100; no personal data in the report), [`CompositeValidatorTest`](../src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java). Code: [`IngestController.ingestOne`](../src/main/java/com/moveinsync/rostersync/api/ingest/IngestController.java) (each record parsed separately), [`CompositeValidator`](../src/main/java/com/moveinsync/rostersync/domain/validation/CompositeValidator.java).

### Module 4: The same change twice

An exact repeat is a no-op; the same version with different content is a conflict, reported and not applied.
```bash
send $HR "$(ev CREATE E1001 1 BLR-01 DAY)"                          # first time
send $HR "$(ev CREATE E1001 1 BLR-01 DAY)"                          # exactly the same again
send $HR "$(ev CREATE E1001 1 BLR-01 DAY ',"shiftCode":"NIGHT"')"   # same version 1, different content
show E1001
```
Output:
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"DUPLICATE"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"REJECTED","errorCode":"VERSION_CONFLICT"}]}
{'employeeId': 'E1001', 'hrVersion': 1, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```
- A duplicate writes nothing and publishes no change event.
- Two different contents under one version mean the source is inconsistent; the second is refused and counted, and the shift stays `DAY`.

Tests: [`ReplayFeedTwiceIT`](../src/test/java/com/moveinsync/rostersync/ReplayFeedTwiceIT.java) (a 1,000-event feed sent twice leaves every table identical), [`VersionPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java). Code: [`VersionPolicy.decide`](../src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java), [`PayloadHasher`](../src/main/java/com/moveinsync/rostersync/domain/PayloadHasher.java) (the content fingerprint).

### Module 5: Changes out of order

The case study's example: HR changes the shift (v2), then transfers the employee (v3); v3 arrives first.
```bash
send $HR "$(ev UPDATE E1001 3 HYD-02 NIGHT)"   # v3: transfer to HYD-02 (still NIGHT) arrives first
send $HR "$(ev UPDATE E1001 2 BLR-01 NIGHT)"   # v2: the shift change arrives late
show E1001
```
Output:
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"STALE"}]}
{'employeeId': 'E1001', 'hrVersion': 3, 'officeCode': 'HYD-02', 'shiftCode': 'NIGHT', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```
Events carry the employee's full HR state, so v3 already contains v2's shift change. The late v2 is ignored and cannot move the employee back to `BLR-01`.

Tests: [`ExampleUseCaseIT.transferV7BeforeShiftV6`](../src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java) (the case study's values), [`ConcurrentApplyIT`](../src/test/java/com/moveinsync/rostersync/ConcurrentApplyIT.java) (32 threads, versions 1–200 shuffled: ends at v200 with no lost or double update). Code: [`ApplyService.decideAndWrite`](../src/main/java/com/moveinsync/rostersync/application/ApplyService.java) (row lock, decision and write in one transaction).

### Module 6: HR vs the transport team

HR owns name, email, phone, office, shift and status; the transport admin owns pickup point and special assistance. Neither can overwrite the other, and admin edits are protected against lost updates.

HR sends a pickup point it does not own:
```bash
send $HR "$(ev UPDATE E1001 4 HYD-02 NIGHT ',"pickupPoint":"Gate 9"')"
show E1001
curl -s $BASE/actuator/prometheus | grep '^hr_owned_field_violation_total'
```
Output:
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{'employeeId': 'E1001', 'hrVersion': 4, 'officeCode': 'HYD-02', 'shiftCode': 'NIGHT', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
hr_owned_field_violation_total{application="hr-roster-sync",field="pickupPoint",tenant="acme"} 1.0
```
The HR update applies; the pickup point is ignored and counted.

The transport admin edits, naming the version the edit is based on (`If-Match`):
```bash
# 1. no If-Match at all
curl -s -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 2"}'; echo
# 2. a correct edit of both transport fields, based on version "0"
curl -s -i -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "0"' \
  -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 2, Tech Park","specialAssistance":"wheelchair"}' | grep -iE '^(HTTP|etag)'
# 3. a second admin, still holding the old version "0"
curl -s -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "0"' -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 5"}'; echo
# 4. the admin tries an HR field
curl -s -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "1"' -H 'Content-Type: application/json' -d '{"officeCode":"BLR-01"}'; echo
# 5. a typo in the field name
curl -s -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "1"' -H 'Content-Type: application/json' -d '{"pickupPont":"Gate 7"}'; echo
# 6. clear one field with null; the field not sent is kept
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH $BASE/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" -H 'If-Match: "1"' -H 'Content-Type: application/json' -d '{"specialAssistance":null}'
show E1001
```
Output:
```
{…,"status":428,"title":"Precondition Required","code":"PRECONDITION_REQUIRED","traceId":"…"}
HTTP/1.1 200
ETag: "1"
{…,"status":412,"title":"Precondition Failed","code":"TRANSPORT_VERSION_MISMATCH","traceId":"…"}
{…,"status":400,"code":"FIELD_NOT_OWNED","traceId":"…","fields":["officeCode"]}
{…,"status":400,"code":"INVALID_REQUEST","traceId":"…","fields":["pickupPont"]}
200
{'employeeId': 'E1001', 'hrVersion': 4, 'officeCode': 'HYD-02', 'shiftCode': 'NIGHT', 'status': 'ACTIVE', 'pickupPoint': 'Gate 2, Tech Park', 'specialAssistance': None, 'transportVersion': 2}
```

HR moves the employee again (v5); the admin's pickup point stays:
```bash
send $HR "$(ev UPDATE E1001 5 BLR-01 DAY)"
show E1001
```
Output:
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{'employeeId': 'E1001', 'hrVersion': 5, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': 'Gate 2, Tech Park', 'specialAssistance': None, 'transportVersion': 2}
```
- Separate versions per owner: `hrVersion` moved 4 → 5 while `transportVersion` stayed at 2.
- A foreign field from the HR feed is ignored and counted; the same mistake from the admin endpoint is rejected, because a batch feed should not block a valid record while an interactive caller can correct the request.
- An edit based on an old version gets 412 and changes nothing; an edit without `If-Match` gets 428; an unknown field name gets 400.

Tests: [`TransportPatchIT`](../src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java), [`EmployeeRepositoryIT.hrWritesNeverTouchTransportColumns`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java), [`FieldOwnershipPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicyTest.java). Code: [`EmployeeField`](../src/main/java/com/moveinsync/rostersync/domain/EmployeeField.java) (the ownership table), [`JdbcEmployeeRepository.updateHrFields`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcEmployeeRepository.java) (the SQL names only HR columns).

### Module 7: One client never sees another

Client globex cannot read, change or reference client acme's data, and cannot tell whether a given acme record exists.
```bash
# globex's admin asks for acme's E1001; acme's admin asks for an id nobody has
curl -s -o /dev/null -w 'globex admin, E1001 -> %{http_code}\n' $BASE/api/v1/employees/E1001 -H "Authorization: Bearer $GLOBEX_ADMIN"
curl -s -o /dev/null -w 'acme admin, E9999 -> %{http_code}\n'   $BASE/api/v1/employees/E9999 -H "Authorization: Bearer $ADMIN"
# compare the two bodies, leaving out the per-request traceId and path
curl -s $BASE/api/v1/employees/E1001 -H "Authorization: Bearer $GLOBEX_ADMIN" | python3 -c 'import sys,json; d=json.load(sys.stdin); d.pop("traceId"); d.pop("instance"); print(d)'
curl -s $BASE/api/v1/employees/E9999 -H "Authorization: Bearer $ADMIN"        | python3 -c 'import sys,json; d=json.load(sys.stdin); d.pop("traceId"); d.pop("instance"); print(d)'
# globex's HR system sends its own E1001, and an employee at acme's office HYD-02
send $GLOBEX_HR "$(ev CREATE E1001 1 CHN-01 DAY)"
send $GLOBEX_HR "$(ev CREATE E2001 1 HYD-02 DAY)"
show E1001
```
Output:
```
globex admin, E1001 -> 404
acme admin, E9999 -> 404
{'status': 404, 'title': 'Not Found', 'type': 'urn:rostersync:error:not-found', 'code': 'NOT_FOUND'}
{'status': 404, 'title': 'Not Found', 'type': 'urn:rostersync:error:not-found', 'code': 'NOT_FOUND'}
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E2001","outcome":"REJECTED","errorCode":"UNKNOWN_OFFICE"}]}
{'employeeId': 'E1001', 'hrVersion': 5, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': 'Gate 2, Tech Park', 'specialAssistance': None, 'transportVersion': 2}
```
- Another client's employee and a non-existent id produce identical 404 bodies; a 403 would confirm the record exists.
- The same id in two clients is two separate employees; acme's E1001 is unchanged.
- Offices belong to a client: `HYD-02` is unknown to globex.
- The client is taken from the access token only, never from the URL or body.

Tests: [`TenantIsolationIT`](../src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java), [`SchemaIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java) (the database refuses a cross-client office reference). Code: [`TenantArgumentResolver`](../src/main/java/com/moveinsync/rostersync/api/security/TenantArgumentResolver.java), [`V1__core_schema.sql`](../src/main/resources/db/migration/V1__core_schema.sql) (every key starts with `tenant_id`).

### Module 8: Each person only what their role allows

An employee reads only their own record; each role reaches only its own endpoints.
```bash
ME=$(tok acme E1001 EMPLOYEE)
curl -s $BASE/api/v1/employees/me -H "Authorization: Bearer $ME" | python3 -c 'import sys,json; print(json.load(sys.stdin)["employeeId"])'
curl -s -o /dev/null -w 'employee reads E1002 -> %{http_code}\n' $BASE/api/v1/employees/E1002 -H "Authorization: Bearer $ME"
curl -s -o /dev/null -w 'admin reads /me -> %{http_code}\n'      $BASE/api/v1/employees/me    -H "Authorization: Bearer $ADMIN"
curl -s -o /dev/null -w 'operator reads E1001 -> %{http_code}\n' $BASE/api/v1/employees/E1001 -H "Authorization: Bearer $OPS"
```
Output:
```
E1001
employee reads E1002 -> 403
admin reads /me -> 403
operator reads E1001 -> 403
```
`/me` takes the employee id from the access token's subject; there is no parameter to change it. The full mapping is in the README's [roles table](../README.md#roles).

Tests: [`EmployeeReadIT`](../src/test/java/com/moveinsync/rostersync/api/employee/EmployeeReadIT.java), [`SecurityIT`](../src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java). Code: [`EmployeeController.me`](../src/main/java/com/moveinsync/rostersync/api/employee/EmployeeController.java).

### Module 9: An exit cancels the cab booking

An exit from HR cancels the employee's future bookings and frees the seats, through a change event. Trips that already ran stay as history.
```bash
# book E1001 on tomorrow's trip; try E1002 on a trip that already ran
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-1","employeeId":"E1001"}'; echo
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-OLD","employeeId":"E1002"}'; echo
# HR: E1001 has left (version 6)
send $HR "$(ev DEACTIVATE E1001 6 BLR-01 DAY)"
sleep 2
curl -s "$BASE/api/v1/bookings?employeeId=E1001" -H "Authorization: Bearer $ADMIN"; echo
```
Output:
```
{"id":"…","tripId":"T-ACME-1","employeeId":"E1001","status":"BOOKED","cancelReason":null,"pickupTime":"…"}
{…,"status":409,"title":"Conflict","code":"TRIP_DEPARTED","traceId":"…"}
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
[{"id":"…","tripId":"T-ACME-1","employeeId":"E1001","status":"CANCELLED","cancelReason":"EMPLOYEE_DEACTIVATED","pickupTime":"…"}]
```

The change events for E1001 on the Kafka topic, in order:
```bash
docker compose exec -T redpanda rpk topic consume rostersync.employee-changes -o :end -f '%k %v\n' | grep '^acme:E1001'
```
Output (seven lines, shown as type · version · changed fields):
```
acme:E1001 EMPLOYEE_CREATED     hrVersion 1  changedFields [firstName, lastName, email, phone, officeCode, shiftCode, status]
acme:E1001 EMPLOYEE_UPDATED     hrVersion 3  changedFields [officeCode, shiftCode]
acme:E1001 EMPLOYEE_UPDATED     hrVersion 4  changedFields []
acme:E1001 TRANSPORT_UPDATED    hrVersion 4  changedFields [pickupPoint, specialAssistance]
acme:E1001 TRANSPORT_UPDATED    hrVersion 4  changedFields [specialAssistance]
acme:E1001 EMPLOYEE_UPDATED     hrVersion 5  changedFields [officeCode, shiftCode]
acme:E1001 EMPLOYEE_DEACTIVATED hrVersion 6  changedFields [status]
```

Rehire and book again; the old exit does not cancel the new booking:
```bash
send $HR "$(ev UPDATE E1001 7 BLR-01 DAY)"
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-1","employeeId":"E1001"}' | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["tripId","status"]})'
```
Output: `APPLIED`, then `{'tripId': 'T-ACME-1', 'status': 'BOOKED'}`.

- The event is written in the same transaction as the change (transactional outbox), so a crash cannot keep one without the other.
- The topic key is `tenant:employee`, which keeps one employee's events in order. The duplicate and the stale v2 from earlier modules produced no events.
- Events carry ids, versions and the names of changed fields; `Gate 2, Tech Park` and `wheelchair` do not appear.
- The consumer records each event id, so a redelivered event cancels nothing a second time.

Tests: [`OutboxIT`](../src/test/java/com/moveinsync/rostersync/OutboxIT.java), [`DeactivationConsumerIT`](../src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java) (including a late duplicate of an old exit after a rehire), [`BookingApiIT`](../src/test/java/com/moveinsync/rostersync/api/booking/BookingApiIT.java). Code: [`OutboxRelay.relayOnce`](../src/main/java/com/moveinsync/rostersync/application/OutboxRelay.java), [`DeactivationHandler.handle`](../src/main/java/com/moveinsync/rostersync/application/DeactivationHandler.java), [`JdbcBookings.cancelFutureBookings`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcBookings.java).

### Module 10: The nightly file: a normal night

The full employee list is compared with the stored data; only real differences are planned and applied.
```bash
night /tmp/night1.csv yes E1001 E1002 N0001 N0002 N0003 N0004 N0005 N0006 N0007 N0008 N0009 N0010
head -3 /tmp/night1.csv; tail -1 /tmp/night1.csv
RUN=$(upload /tmp/night1.csv); echo "run $RUN"
runinfo $RUN
```
Output:
```
#HEADER,tenant=acme,as_of=2026-10-31T10:00:31Z
employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status
E1001,1,Asha,Rao,e1001@acme.example,+919876543210,,BLR-01,DAY,ACTIVE
#TRAILER,count=12
run 8f7983cd-…
{'status': 'COMPLETED', 'activeBefore': 2, 'toCreate': 10, 'toUpdate': 0, 'toDeactivate': 0, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': None, 'applied': 10, 'skipped': 0}
```
- The header names the client (a file for another client is refused; see Module 11) and the snapshot time `as_of`. The trailer states the row count, which is how a complete file is told apart from a cut-off one.
- Only the ten new employees are created. E1001's row is version 1 while version 7 is stored, so it changes nothing; E1002 is unchanged.
- The upload returns `202 Accepted` with a run id; the work runs in the background.

Tests: [`ReconciliationIT`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java), [`ReconciliationDifferTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/ReconciliationDifferTest.java) (one test per row of the comparison table), [`ReconciliationApiIT`](../src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java). Code: [`ReconciliationPlanner.plan`](../src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlanner.java), [`ReconciliationDiffer.diff`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/ReconciliationDiffer.java).

### Module 11: The safety brake

A cut-off file, or one that would deactivate 30 % or more of active employees, is held for an operator. Nothing from it is applied until then.

**(a) A cut-off file.** After Module 10, acme has 12 active employees. This file ends mid-line and has no trailer:
```bash
night /tmp/cut.csv no E1001 E1002 N0001 N0002 N0003
tail -c 60 /tmp/cut.csv; echo
CUT=$(upload /tmp/cut.csv); runinfo $CUT
curl -s $BASE/api/v1/reconciliations/$CUT/errors -H "Authorization: Bearer $OPS"; echo
curl -s $BASE/api/v1/employees/N0010 -H "Authorization: Bearer $ADMIN" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])'
curl -s -X POST $BASE/api/v1/reconciliations/$CUT/reject  -H "Authorization: Bearer $OPS" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])'
curl -s -X POST $BASE/api/v1/reconciliations/$CUT/approve -H "Authorization: Bearer $OPS"; echo
```
Output:
```
cme.example,+919876543210,,BLR-01,DAY,ACTIVE
N0011,1,Asha,Ra
{'status': 'AWAITING_APPROVAL', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 7, 'rowsInvalid': 1, 'trailerOk': False, 'brakeReason': 'FILE_TRUNCATED', 'applied': 0, 'skipped': 0}
[{"lineNo":8,"employeeId":null,"code":"MALFORMED_RECORD"}]
ACTIVE
REJECTED
{…,"status":409,"title":"Conflict","code":"RUN_NOT_AWAITING_APPROVAL","traceId":"…"}
```
Seven employees (N0004–N0010) would have been deactivated. The run is held, N0010 is still `ACTIVE`, the operator rejects it, and a second decision on the same run is refused.

**(b) A complete file that lists 7 of the 12.**
```bash
night /tmp/half.csv yes E1001 E1002 N0001 N0002 N0003 N0004 N0005
HALF=$(upload /tmp/half.csv); runinfo $HALF
curl -s -o /dev/null -w 'approve -> %{http_code}\n' -X POST $BASE/api/v1/reconciliations/$HALF/approve -H "Authorization: Bearer $OPS"
runinfo $HALF
show N0010
```
Output:
```
{'status': 'AWAITING_APPROVAL', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 5, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': 'MISSING_RATIO_EXCEEDED', 'applied': 0, 'skipped': 0}
approve -> 202
{'status': 'COMPLETED', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 5, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': 'MISSING_RATIO_EXCEEDED', 'applied': 5, 'skipped': 0}
{'employeeId': 'N0010', 'hrVersion': 1, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'INACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```
5 of 12 is 41.7 %, above the 30 % threshold, so the run is held. Here the operator approves it and the five are deactivated.

**(c) A file for another client.**
```bash
sed 's/tenant=acme/tenant=globex/' /tmp/night1.csv > /tmp/globex.csv
curl -s -X POST $BASE/api/v1/reconciliations -H "Authorization: Bearer $HR" -F "file=@/tmp/globex.csv"; echo
```
Output: `{…,"status":400,"code":"EXPORT_TENANT_MISMATCH","traceId":"…"}`. It is refused at upload; no run is created.

- A missing or wrong trailer trips the brake even when few people would be affected, and the whole plan is held, creates and updates included.
- A half-written line counts as present, so a damaged line cannot deactivate anyone. The error report holds the line number and code, not the line's content.
- An employee deactivated for being missing keeps their HR version (N0010 is still `hrVersion 1`), so HR's next real version still applies.
- A run can be decided once; concurrent approvals cannot apply a plan twice.

Tests: [`SafetyBrakeTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrakeTest.java) (exactly 30 % trips; just below does not), [`ReconciliationIT`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java) (a late approval still respects live changes made in between), [`ReconciliationApiIT`](../src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java). Code: [`SafetyBrake.evaluate`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrake.java), [`ReconciliationService.execute`](../src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationService.java), [`ApplyService.deactivateMissing`](../src/main/java/com/moveinsync/rostersync/application/ApplyService.java).

### Module 12: The case study at full size: 200,000 employees

The case study's scenario: a client with 200,000 employees sends a file that stops at row 120,000. Nobody is deactivated.

Requires JDK 21 to generate the files ([Temurin 21](https://adoptium.net/temurin/releases/?version=21); `java -version` to check). Uses client globex, so the numbers do not depend on the other modules. About 2 minutes and 30 MB of disk.
```bash
GLOBEX_OPS=$(tok globex ops-1 OPERATOR)
scripts/generate-export.sh --tenant globex --offices BLR-01,CHN-01 --rows 200000 --out data/generated/globex-full.csv
scripts/generate-export.sh --tenant globex --offices BLR-01,CHN-01 --rows 200000 --truncate-at 120000 --out data/generated/globex-cut.csv

# night 1: the full file. All 200,000 are new to the system, so this creates them (about 1–2 minutes)
RUN=$(curl -s -X POST $BASE/api/v1/reconciliations -H "Authorization: Bearer $GLOBEX_HR" -F file=@data/generated/globex-full.csv | python3 -c 'import sys,json;print(json.load(sys.stdin)["runId"])')
until curl -s $BASE/api/v1/reconciliations/$RUN -H "Authorization: Bearer $GLOBEX_OPS" | grep -qE '"status":"(COMPLETED|AWAITING_APPROVAL|FAILED)"'; do sleep 5; done
curl -s $BASE/api/v1/reconciliations/$RUN -H "Authorization: Bearer $GLOBEX_OPS" | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["status","rowsRead","activeBefore","toCreate","toDeactivate","trailerOk","brakeReason","applied"]})'

# night 2: the same file, cut off at row 120,000
CUT=$(curl -s -X POST $BASE/api/v1/reconciliations -H "Authorization: Bearer $GLOBEX_HR" -F file=@data/generated/globex-cut.csv | python3 -c 'import sys,json;print(json.load(sys.stdin)["runId"])')
until curl -s $BASE/api/v1/reconciliations/$CUT -H "Authorization: Bearer $GLOBEX_OPS" | grep -qE '"status":"(COMPLETED|AWAITING_APPROVAL|FAILED)"'; do sleep 2; done
curl -s $BASE/api/v1/reconciliations/$CUT -H "Authorization: Bearer $GLOBEX_OPS" | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["status","rowsRead","rowsInvalid","activeBefore","toDeactivate","missingRatio","trailerOk","brakeReason","applied"]})'
docker compose exec -T postgres psql -U rostersync rostersync -tAc "SELECT count(*) FROM employee WHERE tenant_id = 'globex' AND status = 'ACTIVE'"
```
Output:
```
wrote data/generated/globex-full.csv (up to E200000) in 421 ms
wrote data/generated/globex-cut.csv (up to E120000, truncated, no trailer) in 319 ms
{'status': 'COMPLETED', 'rowsRead': 200000, 'activeBefore': 1, 'toCreate': 200000, 'toDeactivate': 0, 'trailerOk': True, 'brakeReason': None, 'applied': 200000}
{'status': 'AWAITING_APPROVAL', 'rowsRead': 120001, 'rowsInvalid': 1, 'activeBefore': 200001, 'toDeactivate': 79999, 'missingRatio': 0.399993, 'trailerOk': False, 'brakeReason': 'FILE_TRUNCATED', 'applied': 0}
200001
```
`activeBefore` includes the one globex employee from Module 7 (200,000 without it). On the reference laptop, night 1 took 85 s and night 2 took 5 s.

- 79,999 deactivations are held and every employee is still active.
- 79,999 rather than 80,000: the half-written row for E120001 counts as present.
- The file is never loaded whole: it is split into 64 buckets on disk and compared one bucket at a time.
- Clean-up: `rm data/generated/globex-*.csv`.

Tests: [`ExampleUseCaseIT.truncatedFileAt120kDeactivatesNobody`](../src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java), [`ReconciliationBenchmarkIT`](../src/test/java/com/moveinsync/rostersync/ReconciliationBenchmarkIT.java) (200,000 rows on a 256 MB heap; see Module 18). Code: [`ReconciliationPlanner`](../src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlanner.java) (`splitIntoBuckets`, `diffBuckets`), [`BucketAssigner`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/BucketAssigner.java).

### Module 13: What on-call sees

One request followed through its trace id, the structured logs, the metrics, the alert raised by the brake in Module 11, and the dashboard.
```bash
# a request with a chosen trace id (echoed in the response), then the matching log line
curl -s -D - -o /dev/null -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: reviewer-trace-0001' -d "{\"sourceSystem\":\"workday\",\"events\":[$(ev UPDATE E1002 3 BLR-01 DAY ',"email":"bad"')]}" | grep -i '^x-trace-id'
sleep 1
docker compose logs app --no-log-prefix | grep reviewer-trace-0001

# the metrics behind the dashboard
curl -s $BASE/actuator/prometheus | grep -E '^(hr_events_total|reconciliation_brake_tripped_total|outbox_depth)' | sed 's/application="hr-roster-sync",//'

# alerts (after Module 11 the brake alert is firing)
curl -s localhost:9090/api/v1/alerts | python3 -c 'import sys,json; [print(a["labels"]["alertname"], a["state"], a["labels"].get("reason","")) for a in json.load(sys.stdin)["data"]["alerts"]]'
```
Output (metric values depend on the modules run before):
```
X-Trace-Id: reviewer-trace-0001
{"@timestamp":"…","@version":"1","message":"hr event rejected version=3 code=INVALID_EMAIL","logger_name":"com.moveinsync.rostersync.application.ApplyService","thread_name":"http-nio-8080-exec-5","level":"INFO","level_value":20000,"traceId":"reviewer-trace-0001","tenantId":"acme","employeeId":"E1002"}
hr_events_total{code="INVALID_EMAIL",outcome="REJECTED",tenant="acme"} 2.0
hr_events_total{code="NONE",outcome="APPLIED",tenant="acme"} …
…
outbox_depth{application="hr-roster-sync"} 0.0
reconciliation_brake_tripped_total{reason="FILE_TRUNCATED",tenant="acme"} 1.0
reconciliation_brake_tripped_total{reason="MISSING_RATIO_EXCEEDED",tenant="acme"} 1.0
ReconciliationBrakeTripped firing FILE_TRUNCATED
ReconciliationBrakeTripped firing MISSING_RATIO_EXCEEDED
```

| URL | Shows |
|---|---|
| http://localhost:3000 (Grafana, viewing needs no login) | Dashboard **HR Roster Sync**: records per second by outcome, rejections by code, ingest lag, outbox unsent and oldest age, brake trips, cache hit rate |
| http://localhost:9090/alerts | The five alert rules; `ReconciliationBrakeTripped` firing |
| http://localhost:9090/graph | Ad-hoc queries, e.g. `sum by (code) (hr_events_total{outcome="REJECTED"})` |

- One trace id links the response header, the error body and every log line of the request.
- Logs are one JSON object per line, with client and employee as fields.
- Metric labels are client, outcome and code (bounded values), never employee ids.
- The brake alert fires within one evaluation of a trip (about 15 s) and resolves 15 minutes after the last one.

Tests: [`MetricsIT`](../src/test/java/com/moveinsync/rostersync/MetricsIT.java), [`StructuredLoggingIT`](../src/test/java/com/moveinsync/rostersync/StructuredLoggingIT.java), [`TraceIdFilterTest`](../src/test/java/com/moveinsync/rostersync/api/TraceIdFilterTest.java), and the alert rules' unit tests in [`alerts.test.yml`](../ops/prometheus/alerts.test.yml) (run in Module 18). Code: [`alerts.yml`](../ops/prometheus/alerts.yml), [`OutboxMetrics`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/OutboxMetrics.java), [`TraceIdFilter`](../src/main/java/com/moveinsync/rostersync/api/TraceIdFilter.java).

### Module 14: No personal data leaks

A distinctive name and phone number go through one valid and one invalid record; neither value appears in the logs or the metrics.
```bash
send $HR "$(ev CREATE E3001 1 BLR-01 DAY ',"firstName":"Zephyrine","phone":"+919812345678","email":"zephyrine.q@acme.example"')"
send $HR "$(ev CREATE E3002 1 BLR-01 DAY ',"firstName":"Zephyrine","phone":"12ab"')"
docker compose logs app | grep -ciE 'zephyrine|9812345678'
curl -s $BASE/actuator/prometheus | grep -ciE 'zephyrine|9812345678'
```
Output:
```
{"results":[{"index":0,"employeeId":"E3001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E3002","outcome":"REJECTED","errorCode":"INVALID_PHONE"}]}
0
0
```
Logs carry employee ids and codes; error messages are code names; exception messages, which can contain values, are not logged.

Tests: [`NoPiiInLogsIT`](../src/test/java/com/moveinsync/rostersync/NoPiiInLogsIT.java) covers every flow, including the nightly file and the Kafka consumer, and checks all output, the metrics and every error body. It found one real leak during development, since fixed. Code: [`DomainException`](../src/main/java/com/moveinsync/rostersync/domain/DomainException.java), [`GlobalExceptionHandler.unexpected`](../src/main/java/com/moveinsync/rostersync/api/GlobalExceptionHandler.java).

### Module 15: Failure drills: broker down, app killed

Infrastructure failures cause no lost and no doubled changes.

**(a) The message broker goes down.**
```bash
docker compose stop redpanda
send $HR "$(ev UPDATE E1002 2 HYD-02 DAY)"            # HR keeps sending while the broker is down
sleep 8
curl -s $BASE/actuator/prometheus | grep -E '^outbox_(depth|oldest)'
docker compose start redpanda
sleep 20
curl -s $BASE/actuator/prometheus | grep -E '^outbox_(depth|oldest)'
```
Output:
```
{"results":[{"index":0,"employeeId":"E1002","outcome":"APPLIED"}]}
outbox_depth{application="hr-roster-sync"} 1.0
outbox_oldest_age_seconds{application="hr-roster-sync"} 8.08888
outbox_depth{application="hr-roster-sync"} 0.0
outbox_oldest_age_seconds{application="hr-roster-sync"} 0.0
```
The change is accepted while the broker is down; its event waits in the outbox and is published once the broker returns. After a minute of not draining, the `OutboxNotDraining` alert fires.

**(b) The application is killed.**
```bash
docker compose kill app
docker compose up -d --wait app
show E1002
curl -s $BASE/actuator/prometheus | grep -E '^outbox_depth'
```
Output:
```
{'employeeId': 'E1002', 'hrVersion': 2, 'officeCode': 'HYD-02', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
outbox_depth{application="hr-roster-sync"} 0.0
```
- Ingest does not depend on the broker: the outbox is a table in the same database.
- Every committed change survives the kill together with its event. An event sent but not yet marked is sent again after restart, and consumers ignore the duplicate.
- Counters restart from 0 with the process, which is why Module 13 checks the alert before this drill.

Tests: [`BrokerDownIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/messaging/BrokerDownIT.java), [`OutboxCrashRecoveryIT`](../src/test/java/com/moveinsync/rostersync/OutboxCrashRecoveryIT.java) (crashes injected just before and just after the publish). Code: [`OutboxRelay`](../src/main/java/com/moveinsync/rostersync/application/OutboxRelay.java), [`JdbcPendingOutbox`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcPendingOutbox.java) (`SKIP LOCKED`, one active relay at a time).

### Module 16: Look at the data directly

The guarantees as they appear in the database.
```bash
docker compose exec -T postgres psql -U rostersync rostersync \
  -c "SELECT tenant_id, status, count(*) FROM employee GROUP BY 1, 2 ORDER BY 1, 2;" \
  -c "SELECT employee_id, version, outcome FROM employee_event_log WHERE tenant_id = 'acme' AND employee_id = 'E1001' ORDER BY version;" \
  -c "SELECT count(*) AS unsent FROM outbox WHERE published_at IS NULL;"
```
Output after Modules 0–15. Acme's 8 active employees are E1001, E1002, N0001–N0005 and E3001 (each run of Module 17 adds 1,000); the globex row comes from Module 12:
```
 tenant_id |  status  | count
-----------+----------+--------
 acme      | ACTIVE   |      8
 acme      | INACTIVE |      5
 globex    | ACTIVE   | 200001

 employee_id | version | outcome
-------------+---------+---------
 E1001       |       1 | APPLIED
 E1001       |       3 | APPLIED
 E1001       |       4 | APPLIED
 E1001       |       5 | APPLIED
 E1001       |       6 | APPLIED
 E1001       |       7 | APPLIED

 unsent
--------
      0
```
The event log has one row per applied change: no second v1 (the duplicate) and no v2 (the stale event). The unique key on (client, source, employee, version) makes a second row impossible at the database level. `\d employee` in `psql` shows the composite keys and `CHECK` constraints.

Tests: [`SchemaIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java). Code: [`src/main/resources/db/migration/`](../src/main/resources/db/migration/).

### Module 17: Load test

Live ingest under load, with pass/fail thresholds, using the k6 Docker image.
```bash
scripts/loadtest.sh                         # 20 virtual users for 60 s; every record is a real change
VUS=40 DURATION=30s scripts/loadtest.sh     # optional: push harder
```
The script joins the Docker network `hr_roster_sync_default`, which Compose names after the folder. For a clone in a differently named folder, `docker network ls` shows the actual `…_default` name to put in `scripts/loadtest.sh`.

Output of a 15-second reference run (numbers vary by machine):
```
    http_req_duration{name:batch}
    ✓ 'p(99)<2000' p(99)=637.38ms

    http_req_failed
    ✓ 'rate<0.01' rate=0.00%

    checks_succeeded...: 100.00% 3074 out of 3074
    ✓ status 200
    ✓ every record applied

    hr_records_applied.............: 76850  5086.192292/s
    http_req_duration..............: avg=194.9ms  min=14.02ms med=170.71ms p(90)=269.72ms p(95)=332.22ms p(99)=637.37ms max=748.77ms
```
The run passes when both thresholds show `✓` and the exit status is 0. Each request carries 50 records, each a real change (lock, version check, write, event log row, outbox row). Everything runs on one machine together with the database, broker and monitoring, so the figures are a lower bound; full results are in the README, [Performance and cost](../README.md#performance-and-cost).

Code: [`loadtest/ingest.js`](../loadtest/ingest.js).

### Module 18: The automated test suite

Requires JDK 21, and Docker for the integration tests. Each test run starts its own PostgreSQL and Kafka-compatible broker, separate from the compose stack; with both running, Docker needs 6 GB of memory.
```bash
./mvnw test                          # 177 unit tests, about 10 s, no Docker
./mvnw verify                        # + 158 integration tests against real PostgreSQL and Kafka, about 2 min
./mvnw verify -Pbenchmark            # 200,000-row nightly comparison on a 256 MB heap, about 1 min
cat target/benchmark/reconciliation.txt
scripts/check-alerts.sh              # unit tests for the Prometheus alert rules
```
On Windows, use `mvnw.cmd`.

Output: `Tests run: 177, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS` for the unit tests, the same for the integration summary, and `SUCCESS` from `promtool`. Reports are written to `target/surefire-reports` (unit) and `target/failsafe-reports` (integration).

A single test:
```bash
./mvnw test   -Dtest='VersionPolicyTest#transferV7BeforeShiftV6EndsInNewOfficeWithV7Shift'
./mvnw verify -Dit.test=ConcurrentApplyIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
```
Every test class with what it proves: [Part 2](#part-2-the-automated-test-suite). Requirement-to-test mapping: the [traceability matrix](traceability.md).

### Module 19: Prove the tests can fail

Each experiment breaks one rule in the code, runs the test that guards it, and restores the file with `git restore`. Requires JDK 21, and Docker for (c) and (d).

**(a) Exactly 30 % no longer trips the brake.** In [`SafetyBrake.java`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrake.java), method `reachesThreshold`, change `compareTo(limit) >= 0` to `compareTo(limit) > 0`:
```bash
./mvnw test -Dtest=SafetyBrakeTest          # Tests run: 16, Failures: 3 (the exactly-at-threshold cases no longer trip)
git restore src/main/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrake.java
```

**(b) A repeat is treated as a conflict.** In [`VersionPolicy.java`](../src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java), swap `DUPLICATE` and `CONFLICT` in the last line of `decide`:
```bash
./mvnw test -Dtest=VersionPolicyTest        # Tests run: 12, Failures: 3 (two rows of the decision table, and the replay test)
git restore src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java
```

**(c) The consumer forgets handled events.** In [`JdbcProcessedMessages.java`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcProcessedMessages.java), change `== 1` to `>= 0`, so every event looks new:
```bash
./mvnw verify -Dit.test=DeactivationConsumerIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
# fails: lateCopyOfAnOldExitDoesNotCancelBookingsMadeAfterRehire, a late duplicate exit now cancels the new booking
git restore src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcProcessedMessages.java
```

**(d) Deny-by-default removed.** In [`SecurityConfig.java`](../src/main/java/com/moveinsync/rostersync/api/security/SecurityConfig.java), change `.anyRequest().denyAll()` to `.anyRequest().authenticated()`:
```bash
./mvnw verify -Dit.test=SecurityIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
# fails: anUnlistedEndpointIsDeniedEvenWithAValidToken
git restore src/main/java/com/moveinsync/rostersync/api/security/SecurityConfig.java
```

`git status` afterwards shows no modified files.

### Clean-up

```bash
docker compose down        # stop; the data is kept for next time
docker compose down -v     # stop and delete all data
rm -f data/generated/globex-*.csv /tmp/night1.csv /tmp/cut.csv /tmp/half.csv /tmp/globex.csv /tmp/big.json
```

### Checklist

| Claim | Module |
|---|---|
| Unauthenticated, forged and wrong-role calls are refused; unlisted endpoints are closed | 2 |
| One bad record never fails a batch; each rejection has a code and no value | 3 |
| A repeated change is a no-op; same version with different content is a reported conflict | 4 |
| Older versions are ignored; the case study's out-of-order example ends correctly | 5 |
| HR cannot overwrite transport fields; admins cannot overwrite each other or HR fields | 6 |
| Another client's data is indistinguishable from missing data; ids and offices are per client | 7 |
| Employees see only themselves; each role reaches only its endpoints | 8 |
| An exit cancels future bookings through an ordered change event that carries no values | 9 |
| A normal nightly file applies only the real differences | 10 |
| A cut-off file, or ≥ 30 % missing, is held; operators approve or reject, once | 11 |
| 200,000 employees, file cut at 120,000: nobody deactivated | 12 |
| Trace ids, JSON logs, metrics, a firing alert, a dashboard | 13 |
| No personal data in logs or metrics | 14 |
| Broker outage and app crash: nothing lost, nothing doubled | 15 |
| The database enforces one row per applied change | 16 |
| The load test passes its thresholds | 17 |
| 177 unit + 158 integration tests pass; benchmark and alert tests pass | 18 |
| The tests fail when the rules they guard are broken | 19 |

## Part 2: The automated test suite

### Requirements

| To run | Requires |
|---|---|
| Unit tests | JDK 21 ([Temurin](https://adoptium.net/temurin/releases/?version=21)); no Docker needed |
| Integration tests, the benchmark | JDK 21 and Docker running. Each test run starts its own PostgreSQL and Kafka-compatible broker in Docker (Testcontainers), separate from the `docker compose` stack |
| Alert-rule tests | Docker |
| Load test, guided tour | The running stack (`docker compose up -d --build --wait`) |

The Maven wrapper (`./mvnw`) is included, so Maven itself is not required. On Windows, use `mvnw.cmd`.

### The commands

| What | Command | Time |
|---|---|---|
| All unit tests (177) | `./mvnw test` | ~10 s |
| Everything: unit + integration (158) | `./mvnw verify` | ~2 min |
| One unit test class | `./mvnw test -Dtest=VersionPolicyTest` | |
| One unit test method | `./mvnw test -Dtest='VersionPolicyTest#transferV7BeforeShiftV6EndsInNewOfficeWithV7Shift'` | |
| One integration test class | `./mvnw verify -Dit.test=TenantIsolationIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` | |
| One integration test method | `./mvnw verify -Dit.test='ExampleUseCaseIT#transferV7BeforeShiftV6' -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` | |
| The 200,000-row benchmark, 256 MB heap | `./mvnw verify -Pbenchmark` → `target/benchmark/reconciliation.txt` | ~1 min |
| Alert rules (`promtool`) | `scripts/check-alerts.sh` | seconds |
| Load test (k6, against the running stack) | `scripts/loadtest.sh` (`VUS=40 DURATION=2m scripts/loadtest.sh` to change it) | 1 min |
| Every route, end to end | `scripts/demo-tour.sh` (against the running stack) | 30 s |

Results: `target/surefire-reports` (unit), `target/failsafe-reports` (integration), and the console summary (`Tests run: …, Failures: 0`).

### Every test class

| Problem | Test class | Kind | Tests | What it proves | Run it |
|---|---|---|---|---|---|
| 1. Bad data | [`CompositeValidatorTest`](../src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java) | Unit | 19 | Required fields, email, E.164 phone, office; errors never contain the values | `./mvnw test -Dtest=CompositeValidatorTest` |
|  | [`IngestControllerIT`](../src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java) | IT | 13 | The batch endpoint: per-record results, one bad record never fails the batch, no personal data in reports | `./mvnw verify -Dit.test=IngestControllerIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`HrEventTest`](../src/test/java/com/moveinsync/rostersync/domain/HrEventTest.java) | Unit | 5 | The three event types and their rules | `./mvnw test -Dtest=HrEventTest` |
| 2–3. Duplicates, order, concurrency | [`VersionPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java) | Unit | 6 | The apply / duplicate / conflict / stale table, incl. v7-before-v6 | `./mvnw test -Dtest=VersionPolicyTest` |
|  | [`PayloadHasherTest`](../src/test/java/com/moveinsync/rostersync/domain/PayloadHasherTest.java) | Unit | 10 | The content fingerprint is stable and changes when any HR field changes | `./mvnw test -Dtest=PayloadHasherTest` |
|  | [`EmployeeTest`](../src/test/java/com/moveinsync/rostersync/domain/EmployeeTest.java) | Unit | 14 | The employee: create, apply HR, apply transport, deactivate | `./mvnw test -Dtest=EmployeeTest` |
|  | [`ApplyServiceIT`](../src/test/java/com/moveinsync/rostersync/application/ApplyServiceIT.java) | IT | 13 | Every outcome against the database, incl. exits arriving first | `./mvnw verify -Dit.test=ApplyServiceIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ExampleUseCaseIT`](../src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java) | IT | 2 | The case study's two examples: v7 before v6; a file cut at 120,000 of 200,000 | `./mvnw verify -Dit.test=ExampleUseCaseIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ReplayFeedTwiceIT`](../src/test/java/com/moveinsync/rostersync/ReplayFeedTwiceIT.java) | IT | 1 | A 1,000-event feed sent twice changes nothing | `./mvnw verify -Dit.test=ReplayFeedTwiceIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ConcurrentApplyIT`](../src/test/java/com/moveinsync/rostersync/ConcurrentApplyIT.java) | IT (concurrency) | 1 | 32 threads, shuffled versions 1–200 for one employee: no lost or double update | `./mvnw verify -Dit.test=ConcurrentApplyIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
| 4. Field ownership | [`FieldOwnershipPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicyTest.java) | Unit | 11 | Which owner each field has; foreign and unknown fields | `./mvnw test -Dtest=FieldOwnershipPolicyTest` |
|  | [`HrOwnedFieldsTest`](../src/test/java/com/moveinsync/rostersync/domain/HrOwnedFieldsTest.java) | Unit | 5 | HR fields: ownership check, change detection, safe printing | `./mvnw test -Dtest=HrOwnedFieldsTest` |
|  | [`TransportPatchIT`](../src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java) | IT | 11 | Admin edits: If-Match, 412, 428, FIELD_NOT_OWNED, HR updates keep transport fields | `./mvnw verify -Dit.test=TransportPatchIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`EmployeeRepositoryIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java) | IT | 13 | The database layer: HR writes never touch transport columns; version conditions; client scoping | `./mvnw verify -Dit.test=EmployeeRepositoryIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
| 5–6. Nightly file and safety brake | [`ReconciliationDifferTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/ReconciliationDifferTest.java) | Unit | 13 | Every row of the comparison table | `./mvnw test -Dtest=ReconciliationDifferTest` |
|  | [`SafetyBrakeTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrakeTest.java) | Unit | 8 | Thresholds, the exact 30 % boundary, a missing trailer | `./mvnw test -Dtest=SafetyBrakeTest` |
|  | [`BucketAssignerTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/BucketAssignerTest.java) | Unit | 6 | Bucket numbers are stable and spread evenly | `./mvnw test -Dtest=BucketAssignerTest` |
|  | [`ExportReaderTest`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/ExportReaderTest.java) | Unit | 9 | Reading the file: header, trailer, line numbers, cut-off lines | `./mvnw test -Dtest=ExportReaderTest` |
|  | [`ExportGeneratorTest`](../src/test/java/com/moveinsync/rostersync/tools/ExportGeneratorTest.java) | Unit | 4 | The test-file generator writes valid, cut-off and changed files | `./mvnw test -Dtest=ExportGeneratorTest` |
|  | [`ReconciliationPlannerIT`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlannerIT.java) | IT | 5 | Planning: only changes, bad lines by number and code, invalid = present, temp files deleted | `./mvnw verify -Dit.test=ReconciliationPlannerIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ReconciliationIT`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java) | IT | 5 | Normal night, brake, missing trailer, late approval, unreadable file | `./mvnw verify -Dit.test=ReconciliationIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`BrakeThresholdConfigIT`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/BrakeThresholdConfigIT.java) | IT | 2 | The threshold comes from configuration | `./mvnw verify -Dit.test=BrakeThresholdConfigIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ReconciliationApiIT`](../src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java) | IT | 10 | Upload, read, errors, approve, reject, roles over HTTP | `./mvnw verify -Dit.test=ReconciliationApiIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ReconciliationBenchmarkIT`](../src/test/java/com/moveinsync/rostersync/ReconciliationBenchmarkIT.java) | Benchmark | 1 | 200,000 rows in under 60 s on a 256 MB heap (`-Pbenchmark` only) | `./mvnw verify -Pbenchmark` |
| 7–8. Change events and bookings | [`OutboxIT`](../src/test/java/com/moveinsync/rostersync/OutboxIT.java) | IT | 8 | The event is saved in the same transaction; none for duplicates, stale or rejected records | `./mvnw verify -Dit.test=OutboxIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`OutboxRelayIT`](../src/test/java/com/moveinsync/rostersync/application/OutboxRelayIT.java) | IT | 6 | Order per employee, stop at the first failure, one active relay, pipelined sends | `./mvnw verify -Dit.test=OutboxRelayIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`OutboxRelaySchedulerTest`](../src/test/java/com/moveinsync/rostersync/application/OutboxRelaySchedulerTest.java) | Unit | 3 | The relay's timing and backoff | `./mvnw test -Dtest=OutboxRelaySchedulerTest` |
|  | [`OutboxCrashRecoveryIT`](../src/test/java/com/moveinsync/rostersync/OutboxCrashRecoveryIT.java) | IT (failure injection) | 2 | Crash between the database write and the publish, both points | `./mvnw verify -Dit.test=OutboxCrashRecoveryIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`BrokerDownIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/messaging/BrokerDownIT.java) | IT (failure injection) | 1 | Broker outage: ingest continues, the backlog drains in order | `./mvnw verify -Dit.test=BrokerDownIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`DeactivationConsumerIT`](../src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java) | IT | 5 | Exit → future bookings cancelled, seats freed, duplicates and late copies ignored | `./mvnw verify -Dit.test=DeactivationConsumerIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`BookingApiIT`](../src/test/java/com/moveinsync/rostersync/api/booking/BookingApiIT.java) | IT | 6 | Booking rules, and an exit cancelling a booking end to end | `./mvnw verify -Dit.test=BookingApiIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
| 9. Personal data and errors | [`NoPiiInLogsIT`](../src/test/java/com/moveinsync/rostersync/NoPiiInLogsIT.java) | IT | 1 | No personal value in any log line, metric or error body, across every flow | `./mvnw verify -Dit.test=NoPiiInLogsIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`GlobalExceptionHandlerTest`](../src/test/java/com/moveinsync/rostersync/api/GlobalExceptionHandlerTest.java) | Unit | 7 | Every error is a problem document with a code; no exception message leaks | `./mvnw test -Dtest=GlobalExceptionHandlerTest` |
|  | [`TraceIdFilterTest`](../src/test/java/com/moveinsync/rostersync/api/TraceIdFilterTest.java) | Unit | 4 | Every request gets a trace id | `./mvnw test -Dtest=TraceIdFilterTest` |
|  | [`StructuredLoggingIT`](../src/test/java/com/moveinsync/rostersync/StructuredLoggingIT.java) | IT | 1 | Logs are JSON with trace, client and employee ids | `./mvnw verify -Dit.test=StructuredLoggingIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
| 10. Security and client isolation | [`SecurityIT`](../src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java) | IT | 9 | 401 and 403 cases, deny by default | `./mvnw verify -Dit.test=SecurityIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`TenantIsolationIT`](../src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java) | IT | 6 | Another client's data is a 404, identical to a missing id | `./mvnw verify -Dit.test=TenantIsolationIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`EmployeeReadIT`](../src/test/java/com/moveinsync/rostersync/api/employee/EmployeeReadIT.java) | IT | 7 | Admin and employee reads, ETag, roles | `./mvnw verify -Dit.test=EmployeeReadIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`DevTokenIT`](../src/test/java/com/moveinsync/rostersync/api/security/DevTokenIT.java) | IT | 4 | Demo tokens work under the dev profile | `./mvnw verify -Dit.test=DevTokenIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`DevTokenAbsentIT`](../src/test/java/com/moveinsync/rostersync/api/security/DevTokenAbsentIT.java) | IT | 1 | …and don't exist without it | `./mvnw verify -Dit.test=DevTokenAbsentIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`SchemaIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java) | IT | 8 | The database itself refuses bad rows, duplicates and cross-client links | `./mvnw verify -Dit.test=SchemaIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
| 11–12. Monitoring, caching, running | [`MetricsIT`](../src/test/java/com/moveinsync/rostersync/MetricsIT.java) | IT | 3 | Every on-call metric is exposed after real traffic; other endpoints closed | `./mvnw verify -Dit.test=MetricsIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`OfficeCacheTest`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/OfficeCacheTest.java) | Unit | 5 | The office cache, with a fake clock | `./mvnw test -Dtest=OfficeCacheTest` |
|  | [`EmployeeReadCacheTest`](../src/test/java/com/moveinsync/rostersync/application/EmployeeReadCacheTest.java) | Unit | 5 | The employee read cache, with a fake clock | `./mvnw test -Dtest=EmployeeReadCacheTest` |
|  | [`EmployeeReadCacheIT`](../src/test/java/com/moveinsync/rostersync/api/employee/EmployeeReadCacheIT.java) | IT | 4 | Cache behaviour over HTTP; a stale ETag gets 412 | `./mvnw verify -Dit.test=EmployeeReadCacheIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`OfficeDirectoryIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/OfficeDirectoryIT.java) | IT | 5 | Office lookup per client; inactive offices are unknown | `./mvnw verify -Dit.test=OfficeDirectoryIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`DemoProfileIT`](../src/test/java/com/moveinsync/rostersync/DemoProfileIT.java) | IT | 2 | The demo profile's data and tokens | `./mvnw verify -Dit.test=DemoProfileIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ApplicationContextIT`](../src/test/java/com/moveinsync/rostersync/ApplicationContextIT.java) | IT | 2 | The application starts against real PostgreSQL and Kafka | `./mvnw verify -Dit.test=ApplicationContextIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false` |
|  | [`ArchitectureTest`](../src/test/java/com/moveinsync/rostersync/ArchitectureTest.java) | Unit | 3 | The layers stay as designed; the rules have no framework imports | `./mvnw test -Dtest=ArchitectureTest` |

Every case-study requirement maps to its tests in the [traceability matrix](traceability.md). [Part 1](#part-1-verify-every-rule-by-hand) checks the same rules against the running system.

- **Unit** tests check the rules in plain Java and run in milliseconds.
- **IT** (integration) tests run the whole application against real PostgreSQL and Kafka.
- **Failure injection** tests crash or disconnect components deliberately.
- The tests for the key guarantees were confirmed to fail when the guarded code is broken: removing the consumer's event-id check, removing the row lock, changing an alert threshold, and reintroducing the data leak the personal-data test found.
