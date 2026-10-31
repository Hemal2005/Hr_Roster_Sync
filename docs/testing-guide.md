# Testing guide

How to verify HR Roster Sync: first by hand, against the running system, then with the automated test suite. Back to the [README](../README.md).

| Part | For | Time |
|---|---|---|
| [1. Verify every rule by hand](#part-1-verify-every-rule-by-hand) | Reviewers who want to see each rule work: 20 copy-paste modules, each with its real expected output | 30 min core, 60–90 min all |
| [2. The automated test suite](#part-2-the-automated-test-suite) | Running the 335 automated tests, and what each test class proves | 2–5 min |

All commands run from the repository root. The system is started with `docker compose up -d --build --wait` (see the README, [Run it in 5 minutes](../README.md#run-it-in-5-minutes)).

## Part 1: Verify every rule by hand

This part lets you check every promise in the [README](../README.md) yourself: against the running system, by hand, one module at a time. You don't need to read any code to follow it. Each module ends with the automated test that proves the same thing, and the code to read if you want to go deeper.

- **Every output below is real.** It was copied from a run against a fresh stack. Only ids, trace ids and timestamps differ between runs.
- **Modules are independent** once Module 0 is done, except where a module says *"after Module N"*. Skip any you like. The numbers shown assume you run them in order on a fresh stack.
- **Time:** about 60–90 minutes for everything. The core path (Modules 0–11) takes about 30 minutes.

### The modules

| # | Module | What you check | Time | Needs |
|---|---|---|---|---|
| 0 | [Start fresh and load the helpers](#module-0-start-fresh-and-load-the-helpers) | A clean stack and a few shell shortcuts | 5 min | Docker |
| 1 | [Is everything running?](#module-1-is-everything-running) | Five healthy services | 1 min | — |
| 2 | [Who may call what](#module-2-who-may-call-what) | 401, 403, deny by default | 3 min | — |
| 3 | [Bad data never blocks good data](#module-3-bad-data-never-blocks-good-data) | Per-record rejection with a code; envelope errors | 3 min | — |
| 4 | [The same change twice](#module-4-the-same-change-twice) | Duplicate vs conflict | 2 min | — |
| 5 | [Changes out of order](#module-5-changes-out-of-order) | The case study's v7-before-v6 example | 2 min | after 4 |
| 6 | [HR vs the transport team](#module-6-hr-vs-the-transport-team) | Field ownership, `If-Match`, 412 / 428 | 5 min | after 5 |
| 7 | [One client never sees another](#module-7-one-client-never-sees-another) | Identical 404s, separate data, per-client offices | 3 min | after 6 |
| 8 | [Each person only what their role allows](#module-8-each-person-only-what-their-role-allows) | `/me`, role refusals | 2 min | after 4 |
| 9 | [An exit cancels the cab booking](#module-9-an-exit-cancels-the-cab-booking) | Change events, the booking consumer, the topic | 5 min | after 6 |
| 10 | [The nightly file: a normal night](#module-10-the-nightly-file-a-normal-night) | Plan → apply, only real changes | 3 min | after 9 |
| 11 | [The safety brake](#module-11-the-safety-brake) | Cut-off file held; ≥ 30 % held; approve / reject | 5 min | after 10 |
| 12 | [The case study at full size: 200,000 employees](#module-12-the-case-study-at-full-size-200000-employees) | The exact "cut at 120,000" scenario | 5 min | JDK 21 |
| 13 | [What on-call sees](#module-13-what-on-call-sees) | Trace ids, JSON logs, metrics, alerts, dashboard | 5 min | after 11 |
| 14 | [No personal data leaks](#module-14-no-personal-data-leaks) | Names and phones absent from logs and metrics | 2 min | — |
| 15 | [Failure drills: broker down, app killed](#module-15-failure-drills-broker-down-app-killed) | Nothing lost, nothing applied twice | 5 min | after 13 |
| 16 | [Look at the data directly](#module-16-look-at-the-data-directly) | The database itself | 3 min | — |
| 17 | [Load test](#module-17-load-test) | Throughput, p99, pass/fail thresholds | 2 min | — |
| 18 | [The automated test suite](#module-18-the-automated-test-suite) | 177 unit + 158 integration tests, benchmark, alert tests | 5–10 min | JDK 21 |
| 19 | [Prove the tests can fail](#module-19-prove-the-tests-can-fail) | Break a rule, watch its test fail | 5 min | JDK 21 |

Each module has the same parts: **Goal** · **Run** · **Expect** · **Why it matters** · **Automated proof** · **Read the code**.

### Module 0: Start fresh and load the helpers

**Goal:** a clean stack, so your outputs match this guide, and a few shell shortcuts so every later command is one line.

**Run:**
```bash
git clone https://github.com/Hemal2005/Hr_Roster_Sync.git   # skip if you already have it
cd Hr_Roster_Sync                                            # keep this folder name (see Module 17)
docker compose down -v                                       # removes any earlier data; harmless on a first run
docker compose up -d --build --wait                          # 3–5 min the first time, seconds afterwards
bash                                                         # the commands below are tested in bash (macOS defaults to zsh)
```

Now paste this whole block into that terminal. Paste it again in any new terminal. Tokens last 8 hours, so if you get `401` later in the day, paste it again.

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

**Expect:** `ready: 195-character token`. If you see a Python `KeyError` or `JSONDecodeError` instead, the app isn't up yet: run `docker compose ps` and wait until `app` is `healthy`.

**Why it matters:** every later module uses these identities. `HR` is client acme's HR system, `ADMIN` is acme's transport admin, `OPS` is acme's operator who approves nightly runs, and the `GLOBEX_*` tokens belong to a second client.

### Module 1: Is everything running?

**Goal:** confirm the five services are up and the app reports ready.

**Run:**
```bash
docker compose ps --format '{{.Service}} {{.Status}}'
curl -s $BASE/actuator/health; echo
curl -s $BASE/actuator/health/readiness; echo
```

**Expect:**
```
app Up 14 seconds (healthy)
grafana Up 9 seconds
postgres Up 19 seconds (healthy)
prometheus Up 19 seconds (healthy)
redpanda Up 19 seconds (healthy)
{"groups":["liveness","readiness"],"status":"UP"}
{"status":"UP"}
```

**Why it matters:** readiness only turns `UP` once the database migrations have run and the app can serve requests. That's what `--wait` waits for.

**Automated proof:** [`ApplicationContextIT`](../src/test/java/com/moveinsync/rostersync/ApplicationContextIT.java), [`MetricsIT`](../src/test/java/com/moveinsync/rostersync/MetricsIT.java). **Read the code:** [`docker-compose.yml`](../docker-compose.yml), [`Dockerfile`](../Dockerfile).

### Module 2: Who may call what

**Goal:** every endpoint refuses callers without a valid token (401) or without the right role (403), and anything not explicitly allowed is closed.

**Run:**
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

**Expect:**
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

**Why it matters:**
- Every refusal is the same machine-readable problem document, with a `code` and a `traceId`.
- The unlisted path answers 403, not 404: an endpoint someone adds later stays closed until a rule is written for it.
- `/actuator/env` stays closed; only `health` and `prometheus` are open.

**Automated proof:** [`SecurityIT`](../src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java), [`DevTokenIT`](../src/test/java/com/moveinsync/rostersync/api/security/DevTokenIT.java), [`DevTokenAbsentIT`](../src/test/java/com/moveinsync/rostersync/api/security/DevTokenAbsentIT.java) (the token endpoint doesn't exist outside the demo profile). **Read the code:** [`SecurityConfig.apiSecurity`](../src/main/java/com/moveinsync/rostersync/api/security/SecurityConfig.java), [`ProblemResponses`](../src/main/java/com/moveinsync/rostersync/api/security/ProblemResponses.java).

### Module 3: Bad data never blocks good data

**Goal:** one batch with one good record and six different kinds of bad record. Only the bad ones are rejected, each with its own code. Then the three ways the whole request can be malformed.

**Run:**
```bash
send $HR "$(ev CREATE E1002 1 BLR-01 DAY)" \
         "$(ev CREATE E1003 1 BLR-01 DAY ',"email":"not-an-email"')" \
         "$(ev CREATE E1004 1 BLR-01 DAY ',"phone":"98765"')" \
         "$(ev CREATE E1005 1 PUN-03 DAY)" \
         "$(ev CREATE E1006 1 BLR-01 DAY ',"version":"abc"')" \
         '{"type":"CREATE","employeeId":"E1007","version":1,"occurredAt":"2026-10-30T08:00:00Z"}' \
         "$(ev PROMOTE E1008 1 BLR-01 DAY)"
```
In order: valid; bad email; phone not in E.164 format; office `PUN-03`, which is inactive; a version that isn't a number; no name, email, phone, office or shift; an event type that doesn't exist.

**Expect** (formatted for reading):
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

Now break the request itself:
```bash
# no sourceSystem, no events
curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d '{"events":[]}'; echo
# not JSON at all
curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d '{"sourceSystem":'; echo
# 1,001 events (the limit is 1,000)
python3 -c 'import json;print(json.dumps({"sourceSystem":"workday","events":[{}]*1001}))' > /tmp/big.json
curl -s -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d @/tmp/big.json; echo
```

**Expect:**
```
{…,"status":400,"code":"INVALID_REQUEST","traceId":"…","fields":["events","sourceSystem"]}
{…,"status":400,"code":"MALFORMED_REQUEST","traceId":"…"}
{…,"status":400,"code":"INVALID_REQUEST","traceId":"…","fields":["events"]}
```

**Why it matters:**
- The response is always `200` with one result per record, so a client knows exactly which records to fix.
- Index 4 has no `employeeId` because the record couldn't be read at all.
- Rejections name the code and, at most, the field. **The rejected value is never echoed back:** `not-an-email` appears nowhere in the response.
- Only a broken envelope fails the whole request.

**Automated proof:** [`IngestControllerIT`](../src/test/java/com/moveinsync/rostersync/api/ingest/IngestControllerIT.java) (one bad record in 100; no personal data in the report), [`CompositeValidatorTest`](../src/test/java/com/moveinsync/rostersync/domain/validation/CompositeValidatorTest.java). **Read the code:** [`IngestController.ingestOne`](../src/main/java/com/moveinsync/rostersync/api/ingest/IngestController.java) (each record parsed on its own), [`CompositeValidator`](../src/main/java/com/moveinsync/rostersync/domain/validation/CompositeValidator.java).

### Module 4: The same change twice

**Goal:** an exact repeat changes nothing. The same version with *different* content is a conflict, reported and not applied.

**Run:**
```bash
send $HR "$(ev CREATE E1001 1 BLR-01 DAY)"                          # first time
send $HR "$(ev CREATE E1001 1 BLR-01 DAY)"                          # exactly the same again
send $HR "$(ev CREATE E1001 1 BLR-01 DAY ',"shiftCode":"NIGHT"')"   # same version 1, different content
show E1001
```

**Expect:**
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"DUPLICATE"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"REJECTED","errorCode":"VERSION_CONFLICT"}]}
{'employeeId': 'E1001', 'hrVersion': 1, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```

**Why it matters:**
- A retry is harmless: no write, no change event.
- Two *different* versions both labelled `1` mean the source is confused. Guessing which one is right could destroy correct data, so it's refused, counted, and left for a person to look at. The shift is still `DAY`.

**Automated proof:** [`ReplayFeedTwiceIT`](../src/test/java/com/moveinsync/rostersync/ReplayFeedTwiceIT.java) (a 1,000-event feed sent twice leaves the database byte-for-byte identical), [`VersionPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/VersionPolicyTest.java). **Read the code:** [`VersionPolicy.decide`](../src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java) (four outcomes in ten lines), [`PayloadHasher`](../src/main/java/com/moveinsync/rostersync/domain/PayloadHasher.java) (the content fingerprint).

### Module 5: Changes out of order

**Goal:** the case study's example. HR changes the shift (v2), then transfers the employee (v3). The network delivers v3 first.

**Run** (after Module 4):
```bash
send $HR "$(ev UPDATE E1001 3 HYD-02 NIGHT)"   # v3: transfer to HYD-02 (still NIGHT) arrives first
send $HR "$(ev UPDATE E1001 2 BLR-01 NIGHT)"   # v2: the shift change arrives late
show E1001
```

**Expect:**
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"STALE"}]}
{'employeeId': 'E1001', 'hrVersion': 3, 'officeCode': 'HYD-02', 'shiftCode': 'NIGHT', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```

**Why it matters:** every event carries the employee's full HR state, so v3 already contains v2's shift change. Ignoring the older event loses nothing, and the late v2 can't move the employee back to `BLR-01`.

**Automated proof:** [`ExampleUseCaseIT.transferV7BeforeShiftV6`](../src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java) (the case study's own numbers), [`ConcurrentApplyIT`](../src/test/java/com/moveinsync/rostersync/ConcurrentApplyIT.java) (32 threads send versions 1–200 shuffled; the result is always v200, with no lost or double update). **Read the code:** [`ApplyService.decideAndWrite`](../src/main/java/com/moveinsync/rostersync/application/ApplyService.java) (row lock, decision, write, all in one transaction).

### Module 6: HR vs the transport team

**Goal:** HR owns name, email, phone, office, shift and status. The transport admin owns pickup point and special assistance. Neither can overwrite the other, and two admins can't overwrite each other.

**Run** (after Module 5). First, HR sends a pickup point it doesn't own:
```bash
send $HR "$(ev UPDATE E1001 4 HYD-02 NIGHT ',"pickupPoint":"Gate 9"')"
show E1001
curl -s $BASE/actuator/prometheus | grep '^hr_owned_field_violation_total'
```
**Expect:**
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{'employeeId': 'E1001', 'hrVersion': 4, 'officeCode': 'HYD-02', 'shiftCode': 'NIGHT', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
hr_owned_field_violation_total{application="hr-roster-sync",field="pickupPoint",tenant="acme"} 1.0
```
The HR update applied, the pickup point was ignored (still `None`), and the attempt was counted.

Now the transport admin edits, which requires naming the version they saw (`If-Match`):
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
**Expect:**
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

Finally, HR moves the employee again (v5). The admin's pickup point must survive:
```bash
send $HR "$(ev UPDATE E1001 5 BLR-01 DAY)"
show E1001
```
**Expect:**
```
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{'employeeId': 'E1001', 'hrVersion': 5, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': 'Gate 2, Tech Park', 'specialAssistance': None, 'transportVersion': 2}
```

**Why it matters:**
- **Two owners, two versions:** `hrVersion` moved 4 → 5 while `transportVersion` stayed 2. Neither owner's edit can make the other's look stale.
- **The HR feed ignores and counts; the admin endpoint rejects.** A batch from a machine shouldn't block a joiner over a column we'd never use. A person at a screen can fix their request.
- **`If-Match` stops lost updates** between two admins: the second one gets 412 and nothing changes. A write without it is refused (428) rather than trusted.
- A typo is an error, not a silent no-op.

**Automated proof:** [`TransportPatchIT`](../src/test/java/com/moveinsync/rostersync/api/employee/TransportPatchIT.java), [`EmployeeRepositoryIT.hrWritesNeverTouchTransportColumns`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/EmployeeRepositoryIT.java), [`FieldOwnershipPolicyTest`](../src/test/java/com/moveinsync/rostersync/domain/FieldOwnershipPolicyTest.java). **Read the code:** [`EmployeeField`](../src/main/java/com/moveinsync/rostersync/domain/EmployeeField.java) (the ownership table), [`JdbcEmployeeRepository.updateHrFields`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcEmployeeRepository.java) (the SQL names only HR columns).

### Module 7: One client never sees another

**Goal:** client globex can't see, change or reference client acme's data, and can't even tell that it exists.

**Run** (after Module 6):
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

**Expect:**
```
globex admin, E1001 -> 404
acme admin, E9999 -> 404
{'status': 404, 'title': 'Not Found', 'type': 'urn:rostersync:error:not-found', 'code': 'NOT_FOUND'}
{'status': 404, 'title': 'Not Found', 'type': 'urn:rostersync:error:not-found', 'code': 'NOT_FOUND'}
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E2001","outcome":"REJECTED","errorCode":"UNKNOWN_OFFICE"}]}
{'employeeId': 'E1001', 'hrVersion': 5, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': 'Gate 2, Tech Park', 'specialAssistance': None, 'transportVersion': 2}
```

**Why it matters:**
- Another client's employee looks **exactly** like one that doesn't exist. A 403 would confirm that it exists.
- The same id in two clients is two different employees: acme's E1001 is untouched (still v5, BLR-01, Gate 2).
- Offices belong to a client: `HYD-02` is acme's, so it's unknown to globex.
- The client always comes from the token, never from the URL or body, so there's no parameter to tamper with.

**Automated proof:** [`TenantIsolationIT`](../src/test/java/com/moveinsync/rostersync/TenantIsolationIT.java), [`SchemaIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java) (the database itself refuses a cross-client office reference). **Read the code:** [`TenantArgumentResolver`](../src/main/java/com/moveinsync/rostersync/api/security/TenantArgumentResolver.java), [`V1__core_schema.sql`](../src/main/resources/db/migration/V1__core_schema.sql) (every key starts with `tenant_id`).

### Module 8: Each person only what their role allows

**Goal:** an employee reads only their own record; other roles get only their own endpoints.

**Run** (after Module 4):
```bash
ME=$(tok acme E1001 EMPLOYEE)
curl -s $BASE/api/v1/employees/me -H "Authorization: Bearer $ME" | python3 -c 'import sys,json; print(json.load(sys.stdin)["employeeId"])'
curl -s -o /dev/null -w 'employee reads E1002 -> %{http_code}\n' $BASE/api/v1/employees/E1002 -H "Authorization: Bearer $ME"
curl -s -o /dev/null -w 'admin reads /me -> %{http_code}\n'      $BASE/api/v1/employees/me    -H "Authorization: Bearer $ADMIN"
curl -s -o /dev/null -w 'operator reads E1001 -> %{http_code}\n' $BASE/api/v1/employees/E1001 -H "Authorization: Bearer $OPS"
```

**Expect:**
```
E1001
employee reads E1002 -> 403
admin reads /me -> 403
operator reads E1001 -> 403
```

**Why it matters:** `/me` takes the id from the token's subject, and there's no parameter to change it. Each role has exactly the endpoints in the [roles table](../README.md#roles); everything else is denied.

**Automated proof:** [`EmployeeReadIT`](../src/test/java/com/moveinsync/rostersync/api/employee/EmployeeReadIT.java), [`SecurityIT`](../src/test/java/com/moveinsync/rostersync/api/security/SecurityIT.java). **Read the code:** [`EmployeeController.me`](../src/main/java/com/moveinsync/rostersync/api/employee/EmployeeController.java).

### Module 9: An exit cancels the cab booking

**Goal:** when HR says someone left, their future cab bookings are cancelled and the seats freed, automatically, through a change event. Past trips are kept as history.

**Run** (after Module 6):
```bash
# book E1001 on tomorrow's trip; try E1002 on a trip that already ran
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-1","employeeId":"E1001"}'; echo
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-OLD","employeeId":"E1002"}'; echo
# HR: E1001 has left (version 6)
send $HR "$(ev DEACTIVATE E1001 6 BLR-01 DAY)"
sleep 2
curl -s "$BASE/api/v1/bookings?employeeId=E1001" -H "Authorization: Bearer $ADMIN"; echo
```

**Expect:**
```
{"id":"…","tripId":"T-ACME-1","employeeId":"E1001","status":"BOOKED","cancelReason":null,"pickupTime":"…"}
{…,"status":409,"title":"Conflict","code":"TRIP_DEPARTED","traceId":"…"}
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
[{"id":"…","tripId":"T-ACME-1","employeeId":"E1001","status":"CANCELLED","cancelReason":"EMPLOYEE_DEACTIVATED","pickupTime":"…"}]
```

Now look at the real change events on the Kafka topic: everything that happened to E1001, in order:
```bash
docker compose exec -T redpanda rpk topic consume rostersync.employee-changes -o :end -f '%k %v\n' | grep '^acme:E1001'
```
**Expect** (seven lines; shown here as type · version · changed fields):
```
acme:E1001 EMPLOYEE_CREATED     hrVersion 1  changedFields [firstName, lastName, email, phone, officeCode, shiftCode, status]
acme:E1001 EMPLOYEE_UPDATED     hrVersion 3  changedFields [officeCode, shiftCode]
acme:E1001 EMPLOYEE_UPDATED     hrVersion 4  changedFields []
acme:E1001 TRANSPORT_UPDATED    hrVersion 4  changedFields [pickupPoint, specialAssistance]
acme:E1001 TRANSPORT_UPDATED    hrVersion 4  changedFields [specialAssistance]
acme:E1001 EMPLOYEE_UPDATED     hrVersion 5  changedFields [officeCode, shiftCode]
acme:E1001 EMPLOYEE_DEACTIVATED hrVersion 6  changedFields [status]
```

Finally, rehire E1001 and book again. The old exit must not cancel the new booking:
```bash
send $HR "$(ev UPDATE E1001 7 BLR-01 DAY)"
curl -s -X POST $BASE/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"tripId":"T-ACME-1","employeeId":"E1001"}' | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["tripId","status"]})'
```
**Expect:** `APPLIED`, then `{'tripId': 'T-ACME-1', 'status': 'BOOKED'}`.

**Why it matters:**
- **No event is lost:** the event is written in the same database transaction as the change (the "outbox"). A crash can't save the change and lose the event, or the reverse.
- **Order per employee:** the topic key is `tenant:employee`, so one employee's events stay in order. The duplicate (v1 again) and the stale v2 from earlier modules produced no events.
- **No personal data on the topic:** events carry ids, versions and the *names* of changed fields, never the values. `Gate 2, Tech Park` and `wheelchair` aren't there.
- **Exactly once in effect:** the broker may deliver an event twice, and the consumer remembers each event id, so the cancellation happens once.

**Automated proof:** [`OutboxIT`](../src/test/java/com/moveinsync/rostersync/OutboxIT.java), [`DeactivationConsumerIT`](../src/test/java/com/moveinsync/rostersync/DeactivationConsumerIT.java) (including a late duplicate of an old exit arriving after a rehire), [`BookingApiIT`](../src/test/java/com/moveinsync/rostersync/api/booking/BookingApiIT.java). **Read the code:** [`OutboxRelay.relayOnce`](../src/main/java/com/moveinsync/rostersync/application/OutboxRelay.java), [`DeactivationHandler.handle`](../src/main/java/com/moveinsync/rostersync/application/DeactivationHandler.java), [`JdbcBookings.cancelFutureBookings`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcBookings.java).

### Module 10: The nightly file: a normal night

**Goal:** HR sends the full employee list. The system compares it with what it holds, plans only the real differences, and applies them.

**Run** (after Module 9, which leaves acme with two active employees, E1001 and E1002):
```bash
night /tmp/night1.csv yes E1001 E1002 N0001 N0002 N0003 N0004 N0005 N0006 N0007 N0008 N0009 N0010
head -3 /tmp/night1.csv; tail -1 /tmp/night1.csv
RUN=$(upload /tmp/night1.csv); echo "run $RUN"
runinfo $RUN
```

**Expect:**
```
#HEADER,tenant=acme,as_of=2026-10-31T10:00:31Z
employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status
E1001,1,Asha,Rao,e1001@acme.example,+919876543210,,BLR-01,DAY,ACTIVE
#TRAILER,count=12
run 8f7983cd-…
{'status': 'COMPLETED', 'activeBefore': 2, 'toCreate': 10, 'toUpdate': 0, 'toDeactivate': 0, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': None, 'applied': 10, 'skipped': 0}
```

**Why it matters:**
- The **header** names the client (a file for the wrong client is refused, see Module 11) and the moment HR took the snapshot (`as_of`). The **trailer** states how many rows there are, which proves the file is complete.
- Only the ten new joiners are created. E1001's row says version 1, but the system already holds version 7, so the file's row is older and changes nothing. E1002 is unchanged. Each employee is applied through the same rules as the live feed.
- The upload answers immediately with a run id (`202 Accepted`); the work runs in the background.

**Automated proof:** [`ReconciliationIT`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java), [`ReconciliationDifferTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/ReconciliationDifferTest.java) (one test per row of the comparison table), [`ReconciliationApiIT`](../src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java). **Read the code:** [`ReconciliationPlanner.plan`](../src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlanner.java), [`ReconciliationDiffer.diff`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/ReconciliationDiffer.java).

### Module 11: The safety brake

**Goal:** a nightly file that is cut off, or that would deactivate 30 % or more of active employees, is **held** for a person to approve or reject. Nothing is applied until then.

**Run (a) — a cut-off file** (after Module 10: acme now has 12 active employees). The file stops in the middle of a line and has no trailer:
```bash
night /tmp/cut.csv no E1001 E1002 N0001 N0002 N0003
tail -c 60 /tmp/cut.csv; echo
CUT=$(upload /tmp/cut.csv); runinfo $CUT
curl -s $BASE/api/v1/reconciliations/$CUT/errors -H "Authorization: Bearer $OPS"; echo
curl -s $BASE/api/v1/employees/N0010 -H "Authorization: Bearer $ADMIN" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])'
curl -s -X POST $BASE/api/v1/reconciliations/$CUT/reject  -H "Authorization: Bearer $OPS" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])'
curl -s -X POST $BASE/api/v1/reconciliations/$CUT/approve -H "Authorization: Bearer $OPS"; echo
```
**Expect:**
```
cme.example,+919876543210,,BLR-01,DAY,ACTIVE
N0011,1,Asha,Ra
{'status': 'AWAITING_APPROVAL', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 7, 'rowsInvalid': 1, 'trailerOk': False, 'brakeReason': 'FILE_TRUNCATED', 'applied': 0, 'skipped': 0}
[{"lineNo":8,"employeeId":null,"code":"MALFORMED_RECORD"}]
ACTIVE
REJECTED
{…,"status":409,"title":"Conflict","code":"RUN_NOT_AWAITING_APPROVAL","traceId":"…"}
```
Seven people (N0004–N0010) would have been deactivated. Instead the run waits, N0010 is still `ACTIVE`, and the operator rejects it. A second decision on the same run is refused.

**Run (b) — a complete file that drops almost half the staff.** It has a valid trailer but lists only 7 of the 12:
```bash
night /tmp/half.csv yes E1001 E1002 N0001 N0002 N0003 N0004 N0005
HALF=$(upload /tmp/half.csv); runinfo $HALF
curl -s -o /dev/null -w 'approve -> %{http_code}\n' -X POST $BASE/api/v1/reconciliations/$HALF/approve -H "Authorization: Bearer $OPS"
runinfo $HALF
show N0010
```
**Expect:**
```
{'status': 'AWAITING_APPROVAL', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 5, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': 'MISSING_RATIO_EXCEEDED', 'applied': 0, 'skipped': 0}
approve -> 202
{'status': 'COMPLETED', 'activeBefore': 12, 'toCreate': 0, 'toUpdate': 0, 'toDeactivate': 5, 'rowsInvalid': 0, 'trailerOk': True, 'brakeReason': 'MISSING_RATIO_EXCEEDED', 'applied': 5, 'skipped': 0}
{'employeeId': 'N0010', 'hrVersion': 1, 'officeCode': 'BLR-01', 'shiftCode': 'DAY', 'status': 'INACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
```
5 of 12 is 41.7 % (≥ 30 %), so the run is held. This time the operator approves it (a real reorganisation, say), and the five are deactivated.

**Run (c) — a file for the wrong client:**
```bash
sed 's/tenant=acme/tenant=globex/' /tmp/night1.csv > /tmp/globex.csv
curl -s -X POST $BASE/api/v1/reconciliations -H "Authorization: Bearer $HR" -F "file=@/tmp/globex.csv"; echo
```
**Expect:** `{…,"status":400,"code":"EXPORT_TENANT_MISMATCH","traceId":"…"}`. It's refused at once, and no run is created.

**Why it matters:**
- **A truncated file is never trusted:** it trips the brake even if few people would be affected, because a file that stopped early is suspect in every row. The **whole** plan is held, creates and updates included.
- **A broken line isn't "missing":** the half-written line counts as present, so a typo can't deactivate anyone. The error report gives the line number and code, never the line's content.
- **A missing employee keeps their HR version** when deactivated (N0010 is still `hrVersion 1`), so HR's next real event for them still applies.
- **Decide once:** two operators clicking at the same moment can't apply a plan twice.

**Automated proof:** [`SafetyBrakeTest`](../src/test/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrakeTest.java) (exactly 30 % trips; 29.999 % doesn't), [`ReconciliationIT`](../src/test/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationIT.java) (an approval hours later still respects live changes made in between), [`ReconciliationApiIT`](../src/test/java/com/moveinsync/rostersync/api/reconciliation/ReconciliationApiIT.java). **Read the code:** [`SafetyBrake.evaluate`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrake.java), [`ReconciliationService.execute`](../src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationService.java), [`ApplyService.deactivateMissing`](../src/main/java/com/moveinsync/rostersync/application/ApplyService.java).

### Module 12: The case study at full size: 200,000 employees

**Goal:** the case study's exact scenario. A client with 200,000 employees sends a file that stops at row 120,000. Nobody must be deactivated.

**Needs:** JDK 21 on your machine, only to *generate* the files ([Temurin 21](https://adoptium.net/temurin/releases/?version=21); check with `java -version`). Uses client globex, so the numbers don't depend on the other modules. About 2 minutes and 30 MB of disk.

**Run:**
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

**Expect:**
```
wrote data/generated/globex-full.csv (up to E200000) in 421 ms
wrote data/generated/globex-cut.csv (up to E120000, truncated, no trailer) in 319 ms
{'status': 'COMPLETED', 'rowsRead': 200000, 'activeBefore': 1, 'toCreate': 200000, 'toDeactivate': 0, 'trailerOk': True, 'brakeReason': None, 'applied': 200000}
{'status': 'AWAITING_APPROVAL', 'rowsRead': 120001, 'rowsInvalid': 1, 'activeBefore': 200001, 'toDeactivate': 79999, 'missingRatio': 0.399993, 'trailerOk': False, 'brakeReason': 'FILE_TRUNCATED', 'applied': 0}
200001
```
(`activeBefore` counts the one globex employee from Module 7; it's 200,000 if you skipped that module.) On the test laptop, night 1 took 85 s and night 2 took 5 s.

**Why it matters:**
- **This is the case study's question, answered with real numbers:** 79,999 people would have been deactivated, the run is held, and the database still has every one of them active.
- It's 79,999 rather than 80,000 because the half-written row for E120001 counts as present.
- **Memory stays flat:** the file is never loaded whole. It's split into 64 buckets on disk, and one bucket at a time is compared with the database.
- To clean up afterwards: `rm data/generated/globex-*.csv`.

**Automated proof:** [`ExampleUseCaseIT.truncatedFileAt120kDeactivatesNobody`](../src/test/java/com/moveinsync/rostersync/ExampleUseCaseIT.java) (the same scenario), [`ReconciliationBenchmarkIT`](../src/test/java/com/moveinsync/rostersync/ReconciliationBenchmarkIT.java) (200,000 rows on a 256 MB heap; see Module 18). **Read the code:** [`ReconciliationPlanner`](../src/main/java/com/moveinsync/rostersync/application/reconciliation/ReconciliationPlanner.java) (`splitIntoBuckets`, `diffBuckets`), [`BucketAssigner`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/BucketAssigner.java).

### Module 13: What on-call sees

**Goal:** follow one request through its trace id, read the structured logs, the metrics, the alert the brake just raised, and the dashboard.

**Run:**
```bash
# send a request with your own trace id (the response echoes it), then find it in the logs
curl -s -D - -o /dev/null -X POST $BASE/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: reviewer-trace-0001' -d "{\"sourceSystem\":\"workday\",\"events\":[$(ev UPDATE E1002 3 BLR-01 DAY ',"email":"bad"')]}" | grep -i '^x-trace-id'
sleep 1
docker compose logs app --no-log-prefix | grep reviewer-trace-0001

# the metrics behind the dashboard
curl -s $BASE/actuator/prometheus | grep -E '^(hr_events_total|reconciliation_brake_tripped_total|outbox_depth)' | sed 's/application="hr-roster-sync",//'

# alerts (after Module 11 the brake alert is firing)
curl -s localhost:9090/api/v1/alerts | python3 -c 'import sys,json; [print(a["labels"]["alertname"], a["state"], a["labels"].get("reason","")) for a in json.load(sys.stdin)["data"]["alerts"]]'
```

**Expect** (the metric values depend on which modules you ran):
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

Then open:

| Open | Look for |
|---|---|
| http://localhost:3000 (Grafana, no login needed to view) | Dashboard **HR Roster Sync**: records per second by outcome, rejections by code, ingest lag, outbox unsent and oldest age, brake trips, cache hit rate |
| http://localhost:9090/alerts | The five alert rules; `ReconciliationBrakeTripped` firing |
| http://localhost:9090/graph | Try `sum by (code) (hr_events_total{outcome="REJECTED"})` |

**Why it matters:**
- One trace id ties the response, the error body and every log line of a request together.
- Logs are one JSON object per line, with the client and employee as fields, ready for any log tool.
- Metric labels are client, outcome and code: bounded values, never employee ids.
- The brake pages someone within a minute: a held night needs a person.

**Automated proof:** [`MetricsIT`](../src/test/java/com/moveinsync/rostersync/MetricsIT.java), [`StructuredLoggingIT`](../src/test/java/com/moveinsync/rostersync/StructuredLoggingIT.java), [`TraceIdFilterTest`](../src/test/java/com/moveinsync/rostersync/api/TraceIdFilterTest.java), and the alert rules' own unit tests ([`alerts.test.yml`](../ops/prometheus/alerts.test.yml), run in Module 18). **Read the code:** [`alerts.yml`](../ops/prometheus/alerts.yml), [`OutboxMetrics`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/OutboxMetrics.java), [`TraceIdFilter`](../src/main/java/com/moveinsync/rostersync/api/TraceIdFilter.java).

### Module 14: No personal data leaks

**Goal:** send a distinctive name and phone number through a valid record and an invalid one, then search everything the system writes for them.

**Run:**
```bash
send $HR "$(ev CREATE E3001 1 BLR-01 DAY ',"firstName":"Zephyrine","phone":"+919812345678","email":"zephyrine.q@acme.example"')"
send $HR "$(ev CREATE E3002 1 BLR-01 DAY ',"firstName":"Zephyrine","phone":"12ab"')"
docker compose logs app | grep -ciE 'zephyrine|9812345678'
curl -s $BASE/actuator/prometheus | grep -ciE 'zephyrine|9812345678'
```

**Expect:**
```
{"results":[{"index":0,"employeeId":"E3001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E3002","outcome":"REJECTED","errorCode":"INVALID_PHONE"}]}
0
0
```

**Why it matters:** zero matches in the logs and in the metrics, for both the accepted and the rejected record. Logs carry employee ids and codes; error messages are the code names; exception messages, which could contain values, are never logged.

**Automated proof:** [`NoPiiInLogsIT`](../src/test/java/com/moveinsync/rostersync/NoPiiInLogsIT.java). It does this for every flow, including the nightly file and the Kafka consumer, and searches every thread's output, the metrics and every error body. It found a real leak during development, which was fixed. **Read the code:** [`DomainException`](../src/main/java/com/moveinsync/rostersync/domain/DomainException.java), [`GlobalExceptionHandler.unexpected`](../src/main/java/com/moveinsync/rostersync/api/GlobalExceptionHandler.java).

### Module 15: Failure drills: broker down, app killed

**Goal:** break the infrastructure on purpose and check that nothing is lost and nothing is applied twice.

**Run (a) — the message broker goes down:**
```bash
docker compose stop redpanda
send $HR "$(ev UPDATE E1002 2 HYD-02 DAY)"            # HR keeps sending while the broker is down
sleep 8
curl -s $BASE/actuator/prometheus | grep -E '^outbox_(depth|oldest)'
docker compose start redpanda
sleep 20
curl -s $BASE/actuator/prometheus | grep -E '^outbox_(depth|oldest)'
```
**Expect:**
```
{"results":[{"index":0,"employeeId":"E1002","outcome":"APPLIED"}]}
outbox_depth{application="hr-roster-sync"} 1.0
outbox_oldest_age_seconds{application="hr-roster-sync"} 8.08888
outbox_depth{application="hr-roster-sync"} 0.0
outbox_oldest_age_seconds{application="hr-roster-sync"} 0.0
```
HR's change was accepted while the broker was down. Its event waited in the outbox (1 unsent, 8 s old) and was published once the broker came back. If the broker stays down for more than a minute, the `OutboxNotDraining` alert fires.

**Run (b) — the application is killed mid-flight:**
```bash
docker compose kill app
docker compose up -d --wait app
show E1002
curl -s $BASE/actuator/prometheus | grep -E '^outbox_depth'
```
**Expect:**
```
{'employeeId': 'E1002', 'hrVersion': 2, 'officeCode': 'HYD-02', 'shiftCode': 'DAY', 'status': 'ACTIVE', 'pickupPoint': None, 'specialAssistance': None, 'transportVersion': 0}
outbox_depth{application="hr-roster-sync"} 0.0
```

**Why it matters:**
- Ingest doesn't depend on the broker: the outbox is just a table in the same database.
- Every committed change survives a kill, together with its event. An event that was sent but not yet marked as sent is sent again after the restart, and consumers ignore the duplicate.
- Counters start again from 0 after a restart; Prometheus handles that. That's why Module 13 checks the alert *before* this drill.

**Automated proof:** [`BrokerDownIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/messaging/BrokerDownIT.java), [`OutboxCrashRecoveryIT`](../src/test/java/com/moveinsync/rostersync/OutboxCrashRecoveryIT.java) (crashes injected just before and just after the publish). **Read the code:** [`OutboxRelay`](../src/main/java/com/moveinsync/rostersync/application/OutboxRelay.java), [`JdbcPendingOutbox`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcPendingOutbox.java) (`SKIP LOCKED`, and one active relay at a time).

### Module 16: Look at the data directly

**Goal:** see the guarantees in the database itself, not only through the API.

**Run:**
```bash
docker compose exec -T postgres psql -U rostersync rostersync \
  -c "SELECT tenant_id, status, count(*) FROM employee GROUP BY 1, 2 ORDER BY 1, 2;" \
  -c "SELECT employee_id, version, outcome FROM employee_event_log WHERE tenant_id = 'acme' AND employee_id = 'E1001' ORDER BY version;" \
  -c "SELECT count(*) AS unsent FROM outbox WHERE published_at IS NULL;"
```

**Expect** (after Modules 0–15. Acme's 8 active employees are E1001, E1002, N0001–N0005 and E3001, plus 1,000 for each load-test run if you did Module 17 first. The globex row comes from Module 12):
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

**Why it matters:** the event log has exactly one row per applied change. There's no second v1 (the duplicate) and no v2 (the stale event). A unique key on (client, source, employee, version) makes a second copy impossible even if the code had a bug. Try `\d employee` inside `psql` to see the composite keys and the `CHECK` constraints.

**Automated proof:** [`SchemaIT`](../src/test/java/com/moveinsync/rostersync/infrastructure/persistence/SchemaIT.java). **Read the code:** [`src/main/resources/db/migration/`](../src/main/resources/db/migration/).

### Module 17: Load test

**Goal:** measure live ingest under load, with pass/fail thresholds, using the k6 Docker image (nothing to install).

**Run:**
```bash
scripts/loadtest.sh                         # 20 virtual users for 60 s; every record is a real change
VUS=40 DURATION=30s scripts/loadtest.sh     # optional: push harder
```
The script joins Docker network `hr_roster_sync_default`, which Compose names after the folder. If you cloned into a folder with a different name, run `docker network ls`, find the network that ends in `_default`, and edit that name in `scripts/loadtest.sh`.

**Expect** (from a 15-second run on the test laptop; your numbers will differ):
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
**Pass:** both thresholds show `✓`, and the command exits with status 0 (`echo $?`). Watch the Grafana dashboard while it runs: the outbox's unsent count rises and drains within seconds.

**Why it matters:** this measures the expensive path. Each request is 50 records, every one a real change: lock, version check, write, event log row, outbox row. It runs on one machine together with the database, broker and monitoring, so the numbers are a lower bound. The full results, the machine specs and the bottleneck this test found are in [Performance and cost](../README.md#performance-and-cost).

**Read the code:** [`loadtest/ingest.js`](../loadtest/ingest.js).

### Module 18: The automated test suite

**Goal:** run everything the modules above checked by hand, and much more, as automated tests.

**Needs:** JDK 21; Docker running for the integration tests. Each run starts its own throwaway PostgreSQL and Kafka-compatible broker, separate from your stack. With the stack running too, Docker needs its 6 GB.

**Run:**
```bash
./mvnw test                          # 177 unit tests, about 10 s, no Docker
./mvnw verify                        # + 158 integration tests against real PostgreSQL and Kafka, about 2 min
./mvnw verify -Pbenchmark            # 200,000-row nightly comparison on a 256 MB heap, about 1 min
cat target/benchmark/reconciliation.txt
scripts/check-alerts.sh              # unit tests for the Prometheus alert rules
```
(On Windows use `mvnw.cmd`.)

**Expect:** `Tests run: 177, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS` for the first command, the same for the integration summary, and `SUCCESS` from `promtool` for the alert rules. Reports are in `target/surefire-reports` (unit) and `target/failsafe-reports` (integration).

**Run one test of your choice:**
```bash
./mvnw test   -Dtest='VersionPolicyTest#transferV7BeforeShiftV6EndsInNewOfficeWithV7Shift'
./mvnw verify -Dit.test=ConcurrentApplyIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
```
Every test class, what it proves and its command: [Part 2](#part-2-the-automated-test-suite). Every case-study line mapped to its tests: the [traceability matrix](traceability.md).

### Module 19: Prove the tests can fail

**Goal:** a test that can't fail proves nothing. Break a rule in the code, watch the test that guards it fail, then restore the code.

**Needs:** JDK 21 (and Docker for c). Each experiment ends with `git restore`, which puts the file back exactly as committed.

**(a) Make "exactly 30 %" no longer trip the brake.** In [`SafetyBrake.java`](../src/main/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrake.java), method `reachesThreshold`, change `compareTo(limit) >= 0` to `compareTo(limit) > 0`:
```bash
./mvnw test -Dtest=SafetyBrakeTest          # Tests run: 16, Failures: 3 (the exactly-at-threshold cases no longer trip)
git restore src/main/java/com/moveinsync/rostersync/domain/reconciliation/SafetyBrake.java
```

**(b) Treat a repeat as a conflict.** In [`VersionPolicy.java`](../src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java), swap `DUPLICATE` and `CONFLICT` in the last line of `decide`:
```bash
./mvnw test -Dtest=VersionPolicyTest        # Tests run: 12, Failures: 3 (two rows of the decision table, and the replay test)
git restore src/main/java/com/moveinsync/rostersync/domain/VersionPolicy.java
```

**(c) Remove the consumer's memory of handled events.** In [`JdbcProcessedMessages.java`](../src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcProcessedMessages.java), change `== 1` to `>= 0`, so every event looks new:
```bash
./mvnw verify -Dit.test=DeactivationConsumerIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
# fails: lateCopyOfAnOldExitDoesNotCancelBookingsMadeAfterRehire, a late duplicate exit now cancels the new booking
git restore src/main/java/com/moveinsync/rostersync/infrastructure/persistence/JdbcProcessedMessages.java
```

**(d) Open a door.** In [`SecurityConfig.java`](../src/main/java/com/moveinsync/rostersync/api/security/SecurityConfig.java), change `.anyRequest().denyAll()` to `.anyRequest().authenticated()`:
```bash
./mvnw verify -Dit.test=SecurityIT -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false
# fails: anUnlistedEndpointIsDeniedEvenWithAValidToken
git restore src/main/java/com/moveinsync/rostersync/api/security/SecurityConfig.java
```

Finish with `git status`: it should show no modified files.

### Clean up

```bash
docker compose down        # stop; the data is kept for next time
docker compose down -v     # stop and delete all data
rm -f data/generated/globex-*.csv /tmp/night1.csv /tmp/cut.csv /tmp/half.csv /tmp/globex.csv /tmp/big.json
```

### Reviewer's checklist

| ✓ | Claim | Checked in |
|---|---|---|
| ☐ | Unauthenticated, forged and wrong-role calls are refused; unlisted endpoints are closed | Module 2 |
| ☐ | One bad record never fails a batch; each rejection has a code and no value | Module 3 |
| ☐ | A repeated change is a no-op; a same-version different-content change is a reported conflict | Module 4 |
| ☐ | Older versions are ignored; the case study's out-of-order example ends correctly | Module 5 |
| ☐ | HR can't overwrite transport fields; admins can't overwrite each other or HR fields | Module 6 |
| ☐ | Another client's data looks exactly like missing data; ids and offices are per client | Module 7 |
| ☐ | Employees see only themselves; each role only its endpoints | Module 8 |
| ☐ | An exit cancels future bookings through a reliable, ordered, value-free change event | Module 9 |
| ☐ | A normal nightly file applies only the real differences | Module 10 |
| ☐ | A cut-off file, or ≥ 30 % missing, is held; operators approve or reject, once | Module 11 |
| ☐ | 200,000 employees, file cut at 120,000: nobody deactivated | Module 12 |
| ☐ | Trace ids, JSON logs, metrics, a firing alert, a dashboard | Module 13 |
| ☐ | No personal data in logs or metrics | Module 14 |
| ☐ | Broker outage and app crash: nothing lost, nothing doubled | Module 15 |
| ☐ | The database enforces one row per applied change | Module 16 |
| ☐ | Load test passes its thresholds | Module 17 |
| ☐ | 177 unit + 158 integration tests pass; benchmark and alert tests pass | Module 18 |
| ☐ | The tests fail when the rules they guard are broken | Module 19 |

## Part 2: The automated test suite

### What you need

| To run | You need |
|---|---|
| Unit tests | JDK 21 ([Temurin](https://adoptium.net/temurin/releases/?version=21)); no Docker needed |
| Integration tests, the benchmark | JDK 21 and Docker running. Each test run starts its own PostgreSQL and Kafka-compatible broker in Docker (Testcontainers), separate from the `docker compose` stack |
| Alert-rule tests | Docker |
| Load test, guided tour | The running stack (`docker compose up -d --build --wait`) |

The project includes the Maven wrapper (`./mvnw`), so Maven itself doesn't need installing. On Windows use `mvnw.cmd`.

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

Every case-study requirement maps to its tests in the [traceability matrix](traceability.md). The [reviewer's guide](#part-1-verify-every-rule-by-hand) above checks the same rules by hand.

- **Unit** tests check the rules in plain Java, in milliseconds.
- **IT** (integration) tests run the whole application against real PostgreSQL and Kafka.
- **Failure injection** tests crash or disconnect things on purpose.
- The tests for the key guarantees were checked to fail when the guarded code is broken: removing the consumer's event-id check, removing the row lock, changing an alert threshold, and reintroducing the data leak the personal-data test found.
