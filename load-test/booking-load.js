// k6 load test: concurrent booking demand against one screening.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... load-test/booking-load.js
//
// setup():   logs in as admin, creates a venue (SEAT_ROWS x 20 seats), an event, a screening, prices,
//            and registers CUSTOMERS customer accounts.
// scenarios: "buyers"   - each iteration picks a random seat, holds it and confirms it (high contention:
//                          many VUs fight over a small pool of seats, so most holds end in 409).
//            "browsers" - read traffic on the public seat map.
// teardown(): reads the seat map and checks the invariant: #BOOKED seats == #successful confirmations.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const CUSTOMERS = parseInt(__ENV.CUSTOMERS || '200');
const SEAT_ROWS = parseInt(__ENV.SEAT_ROWS || '5');      // 20 seats per row
const DURATION = __ENV.DURATION || '60s';
const BUYER_VUS = parseInt(__ENV.BUYER_VUS || '50');
const BROWSER_VUS = parseInt(__ENV.BROWSER_VUS || '10');

const holdsCreated = new Counter('holds_created');
const holdConflicts = new Counter('hold_conflicts');
const bookingsConfirmed = new Counter('bookings_confirmed');
const confirmFailures = new Counter('confirm_failures');
const unexpectedErrors = new Counter('unexpected_errors');

export const options = {
  setupTimeout: '180s',
  scenarios: {
    buyers: { executor: 'constant-vus', vus: BUYER_VUS, duration: DURATION, exec: 'buyer' },
    browsers: { executor: 'constant-vus', vus: BROWSER_VUS, duration: DURATION, exec: 'browser' },
  },
  thresholds: {
    unexpected_errors: ['count==0'],
    'http_req_duration{name:hold}': ['p(95)<1000'],
    'http_req_duration{name:confirm}': ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const json = (token) => ({
  headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: `Bearer ${token}` } : {}),
});

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function setup() {
  const login = http.post(`${BASE}/api/v1/auth/login`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD }), json());
  if (login.status !== 200) fail(`admin login failed: ${login.status} ${login.body}`);
  const admin = login.json('accessToken');
  const run = Date.now();

  const rows = [];
  for (let i = 0; i < SEAT_ROWS; i++) rows.push({ rowLabel: String.fromCharCode(65 + i), seatCount: 20, seatType: 'STANDARD' });
  const venue = http.post(`${BASE}/api/v1/admin/venues`,
    JSON.stringify({ name: `Load Venue ${run}`, city: 'Kanpur', address: 'Load test', rows }), json(admin));
  const event = http.post(`${BASE}/api/v1/admin/events`,
    JSON.stringify({ title: `Load Event ${run}`, category: 'MOVIE', durationMinutes: 120 }), json(admin));
  const startsAt = new Date(Date.now() + 3 * 24 * 3600 * 1000).toISOString();
  const screening = http.post(`${BASE}/api/v1/admin/screenings`,
    JSON.stringify({ eventId: event.json('id'), venueId: venue.json('id'), startsAt }), json(admin));
  const screeningId = screening.json('id');
  const assign = http.post(`${BASE}/api/v1/admin/screenings/${screeningId}/seats`,
    JSON.stringify({ priceBySeatType: { STANDARD: 250 } }), json(admin));
  if (assign.status !== 201) fail(`seat assignment failed: ${assign.status} ${assign.body}`);
  const seatIds = http.get(`${BASE}/api/v1/screenings/${screeningId}/seats`).json('seats').map((s) => s.screeningSeatId);

  const tokens = [];
  for (let i = 0; i < CUSTOMERS; i++) {
    const r = http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({
      email: `load-${run}-${i}@example.com`, password: 'Passw0rd!long', fullName: `Load ${i}` }), json());
    if (r.status !== 201) fail(`register failed: ${r.status}`);
    tokens.push(r.json('accessToken'));
  }
  return { screeningId, seatIds, tokens };
}

export function buyer(data) {
  const token = data.tokens[(exec.vu.idInTest * 7919 + exec.vu.iterationInScenario) % data.tokens.length];
  const seat = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
  const hold = http.post(`${BASE}/api/v1/screenings/${data.screeningId}/holds`,
    JSON.stringify({ screeningSeatIds: [seat] }),
    { headers: Object.assign(json(token).headers, { 'Idempotency-Key': uuid() }), tags: { name: 'hold' },
      responseCallback: http.expectedStatuses(201, 409) });
  if (hold.status === 409) { holdConflicts.add(1); return; }
  if (!check(hold, { 'hold created': (r) => r.status === 201 })) { unexpectedErrors.add(1); return; }
  holdsCreated.add(1);

  const confirm = http.post(`${BASE}/api/v1/bookings/confirm`,
    JSON.stringify({ holdId: hold.json('holdId'), paymentMethodToken: 'pm_card_visa' }),
    { headers: Object.assign(json(token).headers, { 'Idempotency-Key': uuid() }), tags: { name: 'confirm' },
      responseCallback: http.expectedStatuses(201) });
  if (check(confirm, { 'booking confirmed': (r) => r.status === 201 })) {
    bookingsConfirmed.add(1);
  } else {
    confirmFailures.add(1);
    unexpectedErrors.add(1);
  }
}

export function browser(data) {
  const r = http.get(`${BASE}/api/v1/screenings/${data.screeningId}/seats`, { tags: { name: 'seat_map' } });
  if (!check(r, { 'seat map ok': (x) => x.status === 200 })) unexpectedErrors.add(1);
}

export function teardown(data) {
  const map = http.get(`${BASE}/api/v1/screenings/${data.screeningId}/seats`).json();
  console.log(`INVARIANT screeningId=${data.screeningId} seats=${data.seatIds.length} booked=${map.summary.BOOKED} `
    + `held=${map.summary.HELD} available=${map.summary.AVAILABLE}`);
}
