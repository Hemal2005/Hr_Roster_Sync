#!/usr/bin/env bash
# A guided tour of every rule, against a running stack (docker compose up --build --wait).
# Needs only curl and python3. Uses tenant acme from the demo seed; run it on a fresh
# stack (docker compose down -v first) to see exactly the outputs in docs/curl-examples.md.
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

step() { printf '\n== %s\n' "$1"; }
json() { python3 -c 'import sys,json; print(json.dumps(json.load(sys.stdin), separators=(",", ":")))'; }
field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
token() {
  curl -s -X POST "$BASE/dev/token" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$1\",\"subject\":\"$2\",\"roles\":[\"$3\"]}" | field '["token"]'
}
send() {   # send '<events json array>'
  curl -s -X POST "$BASE/api/v1/hr/events:batch" -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' \
    -d "{\"sourceSystem\":\"workday\",\"events\":$1}" | json
}
event() {  # event TYPE ID VERSION OFFICE SHIFT [extra json fields]
  printf '{"type":"%s","employeeId":"%s","version":%s,"occurredAt":"%s","firstName":"Asha","lastName":"Rao","email":"%s@acme.example","phone":"+919876543210","officeCode":"%s","shiftCode":"%s"%s}' \
    "$1" "$2" "$3" "$NOW" "$(echo "$2" | tr 'A-Z' 'a-z')" "$4" "$5" "${6:-}"
}
show() {   # show ID: the admin's view of an employee, HR and transport parts
  curl -s "$BASE/api/v1/employees/$1" -H "Authorization: Bearer $ADMIN" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["employeeId","hrVersion","officeCode","shiftCode","status","pickupPoint","transportVersion"]})'
}
export_file() {  # export_file NAME TRAILER(yes|no) IDS...: one row per id at version 1, same values as event()
  local file="$WORK/$1" trailer=$2; shift 2
  { echo "#HEADER,tenant=acme,as_of=$NOW"
    echo "employee_id,version,first_name,last_name,email,phone,manager_id,office_code,shift_code,status"
    for id in "$@"; do echo "$id,1,Asha,Rao,$(echo "$id" | tr 'A-Z' 'a-z')@acme.example,+919876543210,,BLR-01,DAY,ACTIVE"; done
    if [ "$trailer" = yes ]; then echo "#TRAILER,count=$#"; else printf 'N0011,1,Asha,Ra'; fi
  } > "$file"
  echo "$file"
}
upload() {
  curl -s -X POST "$BASE/api/v1/reconciliations" -H "Authorization: Bearer $HR" -F "file=@$1" | field '["runId"]'
}
run() {    # run RUN_ID: wait until the background run settles, then print it
  local status
  for _ in $(seq 1 60); do
    status=$(curl -s "$BASE/api/v1/reconciliations/$1" -H "Authorization: Bearer $OPS" | field '["status"]')
    case $status in RUNNING|PLANNED|APPLYING) ;; *) break ;; esac
    sleep 1
  done
  curl -s "$BASE/api/v1/reconciliations/$1" -H "Authorization: Bearer $OPS" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["status","activeBefore","toCreate","toUpdate","toDeactivate","rowsInvalid","trailerOk","brakeReason","applied","skipped"]})'
}

HR=$(token acme workday HR_INTEGRATION)
ADMIN=$(token acme admin-1 ADMIN)
OPS=$(token acme ops-1 OPERATOR)
GLOBEX_ADMIN=$(token globex admin-1 ADMIN)

step "1. A new joiner arrives"
send "[$(event CREATE E1001 1 BLR-01 DAY)]"

step "2. The same event arrives again: nothing changes (idempotency)"
send "[$(event CREATE E1001 1 BLR-01 DAY)]"

step "3. Out of order: v3 (transfer to HYD-02, NIGHT) arrives before v2 (shift change to NIGHT)"
send "[$(event UPDATE E1001 3 HYD-02 NIGHT)]"
send "[$(event UPDATE E1001 2 BLR-01 NIGHT)]"
show E1001

step "4. One batch with bad records: each is rejected on its own, the good one applies"
send "[$(event CREATE E1002 1 BLR-01 DAY), $(event CREATE E1003 1 BLR-01 DAY ',"email":"not-an-email"'), \
$(event CREATE E1004 1 BLR-01 DAY ',"phone":"98765"'), $(event CREATE E1005 1 PUN-03 DAY), \
$(event CREATE E1006 1 BLR-01 DAY ',"version":"abc"')]"

step "5. Field ownership: HR sends a pickup point (ignored), the admin edits it with If-Match"
send "[$(event UPDATE E1001 4 HYD-02 NIGHT ',"pickupPoint":"Gate 9"')]"
curl -s -i -X PATCH "$BASE/api/v1/employees/E1001/transport" -H "Authorization: Bearer $ADMIN" \
  -H 'If-Match: "0"' -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 2, Tech Park"}' \
  | grep -i -E '^(HTTP|etag)' | tr -d '\r'
show E1001
echo "-- same edit with the old ETag:"
curl -s -X PATCH "$BASE/api/v1/employees/E1001/transport" -H "Authorization: Bearer $ADMIN" \
  -H 'If-Match: "0"' -H 'Content-Type: application/json' -d '{"pickupPoint":"Gate 5"}' | json
echo "-- the admin tries an HR-owned field:"
curl -s -X PATCH "$BASE/api/v1/employees/E1001/transport" -H "Authorization: Bearer $ADMIN" \
  -H 'If-Match: "1"' -H 'Content-Type: application/json' -d '{"officeCode":"BLR-01"}' | json

step "6. Another tenant asks for E1001: 404, exactly like an id that doesn't exist"
curl -s -o /dev/null -w 'globex admin -> %{http_code}\n' "$BASE/api/v1/employees/E1001" -H "Authorization: Bearer $GLOBEX_ADMIN"
curl -s -o /dev/null -w 'acme admin, unknown id -> %{http_code}\n' "$BASE/api/v1/employees/E9999" -H "Authorization: Bearer $ADMIN"

step "7. The employee reads their own record"
curl -s "$BASE/api/v1/employees/me" -H "Authorization: Bearer $(token acme E1001 EMPLOYEE)" | field '["employeeId"]'

step "8. E1001 has a cab booked tomorrow, then leaves: the booking is cancelled by the change event"
curl -s -X POST "$BASE/api/v1/bookings" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"tripId":"T-ACME-1","employeeId":"E1001"}' | python3 -c 'import sys,json; d=json.load(sys.stdin); print({k: d.get(k) for k in ["tripId","employeeId","status"]})'
send "[$(event DEACTIVATE E1001 5 HYD-02 NIGHT)]"
sleep 2
curl -s "$BASE/api/v1/bookings?employeeId=E1001" -H "Authorization: Bearer $ADMIN" \
  | python3 -c 'import sys,json; print([{k: b.get(k) for k in ["tripId","status","cancelReason"]} for b in json.load(sys.stdin)])'

step "9. Normal night: the file lists E1002 and ten new joiners, with a trailer"
NIGHT1=$(export_file night1.csv yes E1002 N0001 N0002 N0003 N0004 N0005 N0006 N0007 N0008 N0009 N0010)
run "$(upload "$NIGHT1")"

step "10. The file is cut off: no trailer, the last line is half written"
CUT=$(export_file cut.csv no E1002 N0001 N0002 N0003)
CUT_RUN=$(upload "$CUT")
run "$CUT_RUN"
echo "-- what the operator sees as bad lines:"
curl -s "$BASE/api/v1/reconciliations/$CUT_RUN/errors" -H "Authorization: Bearer $OPS" | json
echo "-- the operator rejects it:"
curl -s -X POST "$BASE/api/v1/reconciliations/$CUT_RUN/reject" -H "Authorization: Bearer $OPS" | field '["status"]'
echo "-- deciding again is refused:"
curl -s -X POST "$BASE/api/v1/reconciliations/$CUT_RUN/approve" -H "Authorization: Bearer $OPS" | field '["code"]'

step "11. A complete file that drops half the staff: the brake holds it, the operator approves"
HALF=$(export_file half.csv yes E1002 N0001 N0002 N0003 N0004 N0005)
HALF_RUN=$(upload "$HALF")
run "$HALF_RUN"
curl -s -X POST "$BASE/api/v1/reconciliations/$HALF_RUN/approve" -H "Authorization: Bearer $OPS" > /dev/null
run "$HALF_RUN"
show N0010

step "12. On-call view: a few of the metrics behind the dashboard"
curl -s "$BASE/actuator/prometheus" | grep -E '^(hr_events_total|reconciliation_brake_tripped_total|outbox_depth)' | sed 's/application="hr-roster-sync",//'
