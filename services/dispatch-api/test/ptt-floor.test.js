import test from 'node:test';
import assert from 'node:assert/strict';
import { createPttFloor } from '../src/ptt/floor.js';

const driverA = { user: { id: 'a' }, membership: { tenant_id: 't1', role: 'driver' } };
const driverB = { user: { id: 'b' }, membership: { tenant_id: 't1', role: 'driver' } };
const otherBase = { user: { id: 'c' }, membership: { tenant_id: 't2', role: 'driver' } };

test('PTT floor permits one speaker per tenant and releases cleanly', () => {
  let now = 1000;
  const floor = createPttFloor({ leaseMs: 12000, now: () => now });
  const a = floor.acquire(driverA);
  assert.equal(a.granted, true);
  assert.equal(floor.acquire(driverB).granted, false);
  assert.equal(floor.acquire(otherBase).granted, true);
  assert.equal(floor.heartbeat(driverA, a.leaseToken).granted, true);
  assert.equal(floor.release(driverA, a.leaseToken).released, true);
  assert.equal(floor.acquire(driverB).granted, true);
});

test('PTT floor expires stale speakers automatically', () => {
  let now = 1000;
  const floor = createPttFloor({ leaseMs: 12000, now: () => now });
  assert.equal(floor.acquire(driverA).granted, true);
  now += 12001;
  assert.equal(floor.acquire(driverB).granted, true);
});
