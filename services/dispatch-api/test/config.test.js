import test from 'node:test';
import assert from 'node:assert/strict';
import { runtimeConfig } from '../src/config.js';
import { addQuery } from '../src/supabase-rest.js';
import { spawn } from 'node:child_process';

test('persistent mode requires url and both modern keys', () => {
  assert.equal(runtimeConfig({ SUPABASE_URL: 'https://x.supabase.co', SUPABASE_SECRET_KEY: 's', SUPABASE_PUBLISHABLE_KEY: 'p' }).persistent, true);
  assert.equal(runtimeConfig({ SUPABASE_URL: 'https://x.supabase.co', SUPABASE_SECRET_KEY: 's' }).persistent, false);
});

test('production refuses to boot without complete Supabase config', () => {
  assert.throws(
    () => runtimeConfig({ NODE_ENV: 'production', SUPABASE_URL: 'https://x.supabase.co', SUPABASE_PUBLISHABLE_KEY: 'p' }),
    /SUPABASE_SECRET_KEY/
  );
  assert.equal(
    runtimeConfig({ NODE_ENV: 'production', SUPABASE_URL: 'https://x.supabase.co', SUPABASE_SECRET_KEY: 's', SUPABASE_PUBLISHABLE_KEY: 'p' }).production,
    true
  );
});

test('production cannot disable operator authentication', () => {
  assert.throws(() => runtimeConfig({
    NODE_ENV: 'production', SUPABASE_URL: 'https://x.supabase.co', SUPABASE_SECRET_KEY: 's',
    SUPABASE_PUBLISHABLE_KEY: 'p', DISPATCH_REQUIRE_AUTH: 'false'
  }), /cannot disable dispatch authentication/);
});

test('postgrest query builder preserves filter syntax', () => {
  const u = addQuery(new URL('https://x.supabase.co/rest/v1/drivers'), { tenant_id: 'eq.abc', select: 'id,status' });
  assert.equal(u.searchParams.get('tenant_id'), 'eq.abc');
  assert.equal(u.searchParams.get('select'), 'id,status');
});

test('persistent booking API rejects unauthenticated creation before database access', async () => {
  const port = 8801;
  const child = spawn(process.execPath, ['services/dispatch-api/src/server.js'], {
    cwd: process.cwd(),
    env: { ...process.env, PORT: String(port), SUPABASE_URL: 'https://example.supabase.co', SUPABASE_SECRET_KEY: 'test-secret', SUPABASE_PUBLISHABLE_KEY: 'test-public', NODE_ENV: 'production', DISPATCH_REQUIRE_AUTH: 'true' },
    stdio: 'ignore'
  });
  try {
    let ready = false;
    for (let i = 0; i < 60; i++) {
      try { if ((await fetch(`http://127.0.0.1:${port}/api/health`)).ok) { ready = true; break; } }
      catch {}
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    assert.equal(ready, true);
    const response = await fetch(`http://127.0.0.1:${port}/api/bookings`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ passengerName: 'Unauthorized', pickup: { label: 'A', lat: 6.8, lng: -58.1 }, destination: { label: 'B' } })
    });
    assert.equal(response.status, 401);
  } finally { child.kill('SIGTERM'); }
});
