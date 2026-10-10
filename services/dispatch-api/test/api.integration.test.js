import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';

const port = 8799;
const base = `http://127.0.0.1:${port}`;
let child;

async function waitForServer() {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${base}/api/health`);
      if (r.ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 80));
  }
  throw new Error('Server did not start');
}

test.before(async () => {
  child = spawn(process.execPath, ['services/dispatch-api/src/server.js'], {
    cwd: process.cwd(),
    env: { ...process.env, PORT: String(port), SUPABASE_URL: '', SUPABASE_SECRET_KEY: '', SUPABASE_PUBLISHABLE_KEY: '', LIVEKIT_URL: '', LIVEKIT_API_KEY: '', LIVEKIT_API_SECRET: '' },
    stdio: 'ignore'
  });
  await waitForServer();
});

test.after(() => child?.kill('SIGTERM'));

test('booking can run through offer, trip start and completion end-to-end', async () => {
  const create = await fetch(`${base}/api/bookings`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ passengerName: 'Pilot Customer', passengers: 1, pickup: { label: 'Albertown', lat: 6.8208, lng: -58.1551 }, destination: { label: 'Diamond' } })
  });
  assert.equal(create.status, 201);
  const booking = await create.json();
  assert.equal(booking.status, 'offering');
  assert.ok(booking.currentOfferDriverId);

  const accept = await fetch(`${base}/api/bookings/${booking.id}/offer-response`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ driverId: booking.currentOfferDriverId, accept: true })
  });
  assert.equal(accept.status, 200);
  const assigned = await accept.json();
  assert.equal(assigned.status, 'assigned');
  assert.ok(assigned.assignedDriverId);

  const start = await fetch(`${base}/api/bookings/${booking.id}/start`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ driverId: assigned.assignedDriverId })
  });
  assert.equal(start.status, 200);
  assert.equal((await start.json()).status, 'in_progress');

  const complete = await fetch(`${base}/api/bookings/${booking.id}/complete`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ driverId: assigned.assignedDriverId })
  });
  assert.equal(complete.status, 200);
  assert.equal((await complete.json()).status, 'completed');

  const snapshot = await (await fetch(`${base}/api/state`)).json();
  assert.equal(snapshot.drivers.find((d) => d.id === assigned.assignedDriverId).status, 'available');
});

test('driver active-offer endpoint returns only the offered job', async () => {
  const create = await fetch(`${base}/api/bookings`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ passengerName: 'Second Customer', passengers: 2, pickup: { label: 'Camp Street', lat: 6.818, lng: -58.15 }, destination: { label: 'Giftland' } })
  });
  assert.equal(create.status, 201);
  const booking = await create.json();
  assert.ok(booking.currentOfferDriverId);
  const response = await fetch(`${base}/api/drivers/${booking.currentOfferDriverId}/active-offer`);
  assert.equal(response.status, 200);
  const payload = await response.json();
  assert.equal(payload.offer.id, booking.id);
  assert.equal(payload.offer.currentOfferDriverId, booking.currentOfferDriverId);
  assert.equal(payload.offer.pickup.label, 'Camp Street');
});

test('driver location update reaches state', async () => {
  const state = await (await fetch(`${base}/api/state`)).json();
  const driver = state.drivers[0];
  const update = await fetch(`${base}/api/drivers/${driver.id}/location`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ lat: 6.81234, lng: -58.12345, accuracyM: 12 })
  });
  assert.equal(update.status, 200);
  const next = await (await fetch(`${base}/api/state`)).json();
  assert.equal(next.drivers.find((d) => d.id === driver.id).location.lat, 6.81234);
});

test('scheduled pickup can be rescheduled through the dispatcher API', async () => {
  const endpoint = `${base}/api/bookings`;
  const input = {
    passengerName: 'API Schedule Test', passengers: 1,
    pickup: { label: 'Camp Street', lat: 6.818, lng: -58.15 },
    destination: { label: 'Diamond' },
    scheduledFor: new Date(Date.now() + 45 * 60_000).toISOString()
  };
  const bad = await fetch(endpoint, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ...input, scheduledFor: new Date(Date.now() - 60_000).toISOString() })
  });
  assert.equal(bad.status, 400);
  assert.match((await bad.json()).error, /future/);

  const create = await fetch(endpoint, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(input)
  });
  assert.equal(create.status, 201);
  const booking = await create.json();
  assert.equal(booking.status, 'scheduled');

  const scheduledFor = new Date(Date.now() + 90 * 60_000).toISOString();
  const update = await fetch(`${endpoint}/${booking.id}/reschedule`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ scheduledFor })
  });
  assert.equal(update.status, 200);
  assert.equal((await update.json()).scheduledFor, scheduledFor);
  const state = await (await fetch(`${base}/api/state`)).json();
  assert.equal(state.bookings.find((item) => item.id === booking.id).scheduledFor, scheduledFor);
});

test('PTT endpoints degrade safely when LiveKit is not configured', async () => {
  const token = await fetch(`${base}/api/ptt/token`);
  assert.equal(token.status, 200);
  const tokenBody = await token.json();
  assert.equal(tokenBody.enabled, false);
  assert.equal(tokenBody.provider, 'livekit');

  const acquire = await fetch(`${base}/api/ptt/floor/acquire`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
  assert.equal(acquire.status, 200);
  const lease = await acquire.json();
  assert.equal(lease.granted, true);
  assert.ok(lease.leaseToken);

  const release = await fetch(`${base}/api/ptt/floor/release`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ leaseToken: lease.leaseToken })
  });
  assert.equal(release.status, 200);
  assert.equal((await release.json()).released, true);
});
