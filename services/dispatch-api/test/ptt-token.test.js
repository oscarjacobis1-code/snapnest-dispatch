import test from 'node:test';
import assert from 'node:assert/strict';
import { createPttToken } from '../src/ptt/livekit-provider.js';

const context = {
  user: { id: '11111111-2222-3333-4444-555555555555', email: 'driver@example.com' },
  membership: { tenant_id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', role: 'driver' },
  driver: { vehicle_plate: 'HC 1234' }
};

test('LiveKit PTT token uses opaque tenant/user identity and stays disabled without credentials', async () => {
  const disabled = await createPttToken(context, {});
  assert.equal(disabled.enabled, false);

  const enabled = await createPttToken(context, {
    LIVEKIT_URL: 'wss://pilot.livekit.cloud',
    LIVEKIT_API_KEY: 'devkey',
    LIVEKIT_API_SECRET: 'a-secret-long-enough-for-signing'
  });
  assert.equal(enabled.enabled, true);
  assert.equal(enabled.roomName, 'dispatch-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
  assert.equal(enabled.identity, 'driver-11111111-2222-3333-4444-555555555555');
  assert.ok(enabled.participantToken.split('.').length === 3);
  const serialized = JSON.stringify(enabled);
  assert.equal(serialized.includes('driver@example.com'), false);
  assert.equal(serialized.includes('HC 1234'), false);
});
