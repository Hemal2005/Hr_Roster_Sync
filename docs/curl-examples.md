# curl examples — a guided tour of every rule

Every request below was run against a fresh one-command stack. The outputs are copied from that run; only trace ids differ between runs.

```bash
docker compose down -v && docker compose up --build --wait   # a fresh stack with the demo data
scripts/demo-tour.sh                                         # runs all of the below in order
```

[`scripts/demo-tour.sh`](../scripts/demo-tour.sh) needs only `curl` and `python3`. To try requests by hand, use the commands below in the same order. A Postman collection with the same requests is in [`postman_collection.json`](postman_collection.json).

## 0. Tokens

The demo profile issues tokens for any demo identity. It's for local evaluation only and off unless asked for; see the README, "Run it".

```bash
tok() { curl -s -X POST localhost:8080/dev/token -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$1\",\"subject\":\"$2\",\"roles\":[\"$3\"]}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'; }
HR=$(tok acme workday HR_INTEGRATION); ADMIN=$(tok acme admin-1 ADMIN); OPS=$(tok acme ops-1 OPERATOR)
GLOBEX_ADMIN=$(tok globex admin-1 ADMIN)
```

## 1. A new joiner arrives

```bash
curl -s -X POST localhost:8080/api/v1/hr/events:batch -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' -d '{
  "sourceSystem": "workday",
  "events": [{"type":"CREATE","employeeId":"E1001","version":1,"occurredAt":"2026-10-31T10:00:00Z",
              "firstName":"Asha","lastName":"Rao","email":"e1001@acme.example","phone":"+919876543210",
              "officeCode":"BLR-01","shiftCode":"DAY"}]}'
```
```json
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
```

## 2. The same event again: nothing changes (idempotency)

Send exactly the same request again:
```json
{"results":[{"index":0,"employeeId":"E1001","outcome":"DUPLICATE"}]}
```
Same version and same content, so no write and no change event.

## 3. Out of order: v3 arrives before v2 (the case study's example)

HR made v2 (shift → NIGHT) and then v3 (transfer to HYD-02, still NIGHT); v3 arrives first. Send the v3 event (`"type":"UPDATE","version":3,"officeCode":"HYD-02","shiftCode":"NIGHT"`), then the v2 event (`"version":2,"officeCode":"BLR-01","shiftCode":"NIGHT"`):
```json
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
{"results":[{"index":0,"employeeId":"E1001","outcome":"STALE"}]}
```
```bash
curl -s localhost:8080/api/v1/employees/E1001 -H "Authorization: Bearer $ADMIN"
```
```
employeeId E1001, hrVersion 3, officeCode HYD-02, shiftCode NIGHT, status ACTIVE, pickupPoint null, transportVersion 0
```
Events carry the full HR state, so v3 already contains the NIGHT shift and ignoring the older v2 loses nothing.

## 4. One batch, several bad records

One batch with five events: a valid one, a bad email, a phone that isn't E.164, the inactive office `PUN-03`, and `"version":"abc"`:
```json
{"results":[
  {"index":0,"employeeId":"E1002","outcome":"APPLIED"},
  {"index":1,"employeeId":"E1003","outcome":"REJECTED","errorCode":"INVALID_EMAIL"},
  {"index":2,"employeeId":"E1004","outcome":"REJECTED","errorCode":"INVALID_PHONE"},
  {"index":3,"employeeId":"E1005","outcome":"REJECTED","errorCode":"UNKNOWN_OFFICE"},
  {"index":4,"outcome":"REJECTED","errorCode":"MALFORMED_RECORD"}]}
```
Each record is handled on its own; the report gives index, id and code, never the rejected values.

## 5. Field ownership: HR vs the transport admin

HR sends v4 with a `"pickupPoint":"Gate 9"`. It applies, but the pickup point is ignored and counted:
```json
{"results":[{"index":0,"employeeId":"E1001","outcome":"APPLIED"}]}
```
The admin edits the pickup point, naming the version they saw (`If-Match`):
```bash
curl -s -i -X PATCH localhost:8080/api/v1/employees/E1001/transport -H "Authorization: Bearer $ADMIN" \
  -H 'If-Match: "0"' -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 2, Tech Park"}'
```
```
HTTP/1.1 200
ETag: "1"
employeeId E1001, hrVersion 4, officeCode HYD-02, shiftCode NIGHT, status ACTIVE, pickupPoint Gate 2, Tech Park, transportVersion 1
```
The same edit again with the old `If-Match: "0"` (someone else changed it meanwhile):
```json
{"instance":"/api/v1/employees/E1001/transport","status":412,"title":"Precondition Failed",
 "type":"urn:rostersync:error:transport-version-mismatch","code":"TRANSPORT_VERSION_MISMATCH","traceId":"…"}
```
The admin tries an HR-owned field (`{"officeCode":"BLR-01"}` with `If-Match: "1"`):
```json
{"instance":"/api/v1/employees/E1001/transport","status":400,"title":"Bad Request",
 "type":"urn:rostersync:error:field-not-owned","code":"FIELD_NOT_OWNED","traceId":"…","fields":["officeCode"]}
```

## 6. Another tenant: 404, exactly like an id that doesn't exist

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/employees/E1001 -H "Authorization: Bearer $GLOBEX_ADMIN"
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/employees/E9999 -H "Authorization: Bearer $ADMIN"
```
```
404
404
```

## 7. An employee reads their own record

```bash
curl -s localhost:8080/api/v1/employees/me -H "Authorization: Bearer $(tok acme E1001 EMPLOYEE)"
```
Returns E1001's record. The id comes from the token's subject, so an employee can't ask for anyone else.

## 8. An exit cancels the cab booking (change events)

```bash
curl -s -X POST localhost:8080/api/v1/bookings -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"tripId":"T-ACME-1","employeeId":"E1001"}'
```
```
tripId T-ACME-1, employeeId E1001, status BOOKED
```
HR sends the exit (`"type":"DEACTIVATE","version":5`) → `APPLIED`. Two seconds later:
```bash
curl -s "localhost:8080/api/v1/bookings?employeeId=E1001" -H "Authorization: Bearer $ADMIN"
```
```
[tripId T-ACME-1, status CANCELLED, cancelReason EMPLOYEE_DEACTIVATED]
```
The exit wrote a change event in the same transaction. The relay published it, and the booking consumer cancelled the future booking and freed the seat.

## 9. The nightly file: a normal night

The file format:
```
#HEADER,tenant=acme,as_of=2026-10-31T10:00:00Z
employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status
E1002,1,Asha,Rao,e1002@acme.example,+919876543210,,BLR-01,DAY,ACTIVE
N0001,1,Asha,Rao,n0001@acme.example,+919876543210,,BLR-01,DAY,ACTIVE
…
#TRAILER,count=11
```
```bash
RUN=$(curl -s -X POST localhost:8080/api/v1/reconciliations -H "Authorization: Bearer $HR" -F file=@night1.csv \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["runId"])')      # 202 {"runId":…,"status":"RUNNING"}
curl -s localhost:8080/api/v1/reconciliations/$RUN -H "Authorization: Bearer $OPS"
```
```
status COMPLETED, activeBefore 1, toCreate 10, toUpdate 0, toDeactivate 0, rowsInvalid 0, trailerOk true,
brakeReason null, applied 10, skipped 0
```
E1002 is unchanged (same version, same content), so the plan only creates the ten new joiners. Larger files: `scripts/generate-export.sh --rows 200000`.

## 10. The file is cut off: the safety brake

The next file stops after four rows, in the middle of a fifth, with no trailer:
```
status AWAITING_APPROVAL, activeBefore 11, toCreate 0, toUpdate 0, toDeactivate 7, rowsInvalid 1, trailerOk false,
brakeReason FILE_TRUNCATED, applied 0, skipped 0
```
Nothing has changed: 7 people would have been deactivated, so the run waits for an operator. The bad lines:
```bash
curl -s localhost:8080/api/v1/reconciliations/$RUN/errors -H "Authorization: Bearer $OPS"
```
```json
[{"lineNo":7,"employeeId":null,"code":"MALFORMED_RECORD"}]
```
The operator rejects it, and a second decision is refused:
```bash
curl -s -X POST localhost:8080/api/v1/reconciliations/$RUN/reject  -H "Authorization: Bearer $OPS"   # status REJECTED
curl -s -X POST localhost:8080/api/v1/reconciliations/$RUN/approve -H "Authorization: Bearer $OPS"   # 409 RUN_NOT_AWAITING_APPROVAL
```

## 11. A complete file that drops almost half the staff

A file with a trailer that lists only E1002 and N0001–N0005. 5 of 11 active employees would be deactivated, which is ≥ 30 %:
```
status AWAITING_APPROVAL, activeBefore 11, toDeactivate 5, trailerOk true, brakeReason MISSING_RATIO_EXCEEDED, applied 0
```
The operator approves (`POST …/approve` → `202`), and the plan is applied:
```
status COMPLETED, toDeactivate 5, applied 5, skipped 0
N0010: status INACTIVE, hrVersion 1
```
A deactivation for a missing employee keeps their HR version, so HR's real next version still applies.

## 12. What on-call sees

```bash
curl -s localhost:8080/actuator/prometheus | grep -E '^(hr_events_total|reconciliation_brake_tripped_total|outbox_depth)'
```
```
hr_events_total{code="INVALID_EMAIL",outcome="REJECTED",tenant="acme"} 1.0
hr_events_total{code="INVALID_PHONE",outcome="REJECTED",tenant="acme"} 1.0
hr_events_total{code="MALFORMED_RECORD",outcome="REJECTED",tenant="acme"} 1.0
hr_events_total{code="NONE",outcome="APPLIED",tenant="acme"} 15.0
hr_events_total{code="NONE",outcome="DUPLICATE",tenant="acme"} 1.0
hr_events_total{code="NONE",outcome="STALE",tenant="acme"} 1.0
hr_events_total{code="UNKNOWN_OFFICE",outcome="REJECTED",tenant="acme"} 1.0
outbox_depth{application="hr-roster-sync"} 5.0
reconciliation_brake_tripped_total{reason="FILE_TRUNCATED",tenant="acme"} 1.0
reconciliation_brake_tripped_total{reason="MISSING_RATIO_EXCEEDED",tenant="acme"} 1.0
```
(`application="hr-roster-sync"` is removed from most lines here for width.) `outbox_depth` is 5 because the five deactivations had just been committed and were not yet published; a moment later it is 0. The same numbers are on the Grafana dashboard at http://localhost:3000, and each brake trip raises the `ReconciliationBrakeTripped` alert at http://localhost:9090/alerts within one evaluation (seen in an earlier live run).

## Endpoints

| Method and path | Role | Purpose |
|---|---|---|
| `POST /api/v1/hr/events:batch` | `HR_INTEGRATION` | Up to 1,000 HR events, a result per record |
| `GET /api/v1/employees/{id}` | `ADMIN` | One employee; `ETag` = transport version |
| `GET /api/v1/employees/me` | `EMPLOYEE` | Own record (id from the token) |
| `PATCH /api/v1/employees/{id}/transport` | `ADMIN` | Pickup point, special assistance; `If-Match` required |
| `POST /api/v1/bookings`, `GET /api/v1/bookings?employeeId=` | `ADMIN` | Book a seat; list bookings |
| `POST /api/v1/reconciliations` | `HR_INTEGRATION`, `OPERATOR` | Upload a nightly file → `202 {runId}` |
| `GET /api/v1/reconciliations/{id}`, `…/errors` | `OPERATOR` | The run and its bad lines |
| `POST /api/v1/reconciliations/{id}/approve`, `…/reject` | `OPERATOR` | Decide a held run |
| `POST /dev/token` | none (demo/dev profiles only) | A token for any demo identity |
| `GET /actuator/health`, `/actuator/prometheus` | none | Health; metrics |
