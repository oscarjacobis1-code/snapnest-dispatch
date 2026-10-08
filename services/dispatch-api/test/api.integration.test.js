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
    env: { ...process.env, PORT: String(port), SUPABASE_URL: '', SUPABASE_SECRET_KEY: '', SUPABASE_PUBLISHABLE_KEY: '' },
    stdio: 'ignore'
  });
  await waitForServer();
});

test.after(() => child?.kill('SIGTERM'));

test('booking can be created, offered and accepted end-to-end', async () => {
  const create = await fetch(`${base}/api/bookings`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ passengerName: 'Pilot Customer', passengers: 1, pickup: { label: 'Albertown', lat: 6.8208, lng: -58.1551 }, destination: { label: 'Diamond' } })
  });
  assert.equal(create.status, 201);
  const booking = await create.json();
  assert.equal(booking.status, 'offering');
  assert.ok(booking.currentOfferDriverId);

  const accept = await fetch(`${base}/api/bookings/${booking.id}/offer-response`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ driverId: booking.currentOfferDriverId, accept: true })
  });
  assert.equal(accept.status, 200);
  const assigned = await accept.json();
  assert.equal(assigned.status, 'assigned');
  assert.ok(assigned.assignedDriverId);

  const snapshot = await (await fetch(`${base}/api/state`)).json();
  const driver = snapshot.drivers.find((d) => d.id === assigned.assignedDriverId);
  assert.equal(driver.status, 'busy');
});

test('driver location update reaches state', async () => {
  const state = await (await fetch(`${base}/api/state`)).json();
  const driver = state.drivers[0];
  const update = await fetch(`${base}/api/drivers/${driver.id}/location`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ lat: 6.81234, lng: -58.12345, accuracyM: 12 })
  });
  assert.equal(update.status, 200);
  const next = await (await fetch(`${base}/api/state`)).json();
  assert.equal(next.drivers.find((d) => d.id === driver.id).location.lat, 6.81234);
});
