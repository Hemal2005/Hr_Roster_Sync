// Load test for POST /api/v1/hr/events:batch. Each virtual user owns BATCH employees:
// its first request creates them, every later one sends the next version of all of them
// with a changed shift, so every record is a real change (row lock, update, event log and
// outbox row), not a cheap duplicate. Run with scripts/loadtest.sh.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const BATCH = parseInt(__ENV.BATCH || '50', 10);
const VUS = parseInt(__ENV.VUS || '20', 10);

const applied = new Counter('hr_records_applied');
const notApplied = new Counter('hr_records_not_applied');

export const options = {
  scenarios: {
    ingest: { executor: 'constant-vus', vus: VUS, duration: __ENV.DURATION || '60s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:batch}': ['p(99)<2000'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const res = http.post(`${BASE_URL}/dev/token`,
    JSON.stringify({ tenantId: 'acme', subject: 'loadtest', roles: ['HR_INTEGRATION'] }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) {
    throw new Error(`could not get a token from /dev/token (status ${res.status}); is the demo profile on?`);
  }
  // A prefix per run, so a second run creates new employees instead of sending stale versions.
  return { token: res.json('token'), run: Date.now().toString(36) };
}

export default function (data) {
  const vu = exec.vu.idInTest;
  const version = exec.vu.iterationInScenario + 1;
  const now = new Date().toISOString();
  const events = [];
  for (let i = 0; i < BATCH; i++) {
    const id = `LT-${data.run}-${vu}-${i}`;
    events.push({
      type: version === 1 ? 'CREATE' : 'UPDATE',
      employeeId: id,
      version,
      occurredAt: now,
      firstName: 'Load',
      lastName: 'Test',
      email: `${id.toLowerCase()}@load.example`,
      phone: '+919876543210',
      managerId: 'E000001',
      officeCode: i % 2 === 0 ? 'BLR-01' : 'HYD-02',
      shiftCode: version % 2 === 0 ? 'NIGHT' : 'DAY',
    });
  }

  const res = http.post(`${BASE_URL}/api/v1/hr/events:batch`,
    JSON.stringify({ sourceSystem: 'loadtest', events }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` }, tags: { name: 'batch' } });

  const results = res.status === 200 ? res.json('results') : [];
  const appliedHere = results.filter((r) => r.outcome === 'APPLIED').length;
  applied.add(appliedHere);
  notApplied.add(BATCH - appliedHere);
  check(res, {
    'status 200': (r) => r.status === 200,
    'every record applied': () => appliedHere === BATCH,
  });
}
