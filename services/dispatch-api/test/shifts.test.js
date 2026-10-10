import test from 'node:test';
import assert from 'node:assert/strict';
import { createRuntimeStore } from '../src/runtime-store.js';

test('driver shift opens on duty and closes only when offline', async () => {
  const { store } = createRuntimeStore({});
  await store.setDriverStatus('d1', 'available');
  let shift = await store.driverShiftSummary('d1');
  assert.equal(shift.active, true);
  assert.ok(shift.startedAt);

  await store.setDriverStatus('d1', 'unavailable');
  shift = await store.driverShiftSummary('d1');
  assert.equal(shift.active, true);

  await store.setDriverStatus('d1', 'offline');
  shift = await store.driverShiftSummary('d1');
  assert.equal(shift.active, false);
  assert.ok(shift.endedAt);
});
