import test from 'node:test';
import assert from 'node:assert/strict';
import { runtimeConfig } from '../src/config.js';
import { addQuery } from '../src/supabase-rest.js';

test('persistent mode requires url and both modern keys', () => {
  assert.equal(runtimeConfig({ SUPABASE_URL: 'https://x.supabase.co', SUPABASE_SECRET_KEY: 's', SUPABASE_PUBLISHABLE_KEY: 'p' }).persistent, true);
  assert.equal(runtimeConfig({ SUPABASE_URL: 'https://x.supabase.co', SUPABASE_SECRET_KEY: 's' }).persistent, false);
});

test('postgrest query builder preserves filter syntax', () => {
  const u = addQuery(new URL('https://x.supabase.co/rest/v1/drivers'), { tenant_id: 'eq.abc', select: 'id,status' });
  assert.equal(u.searchParams.get('tenant_id'), 'eq.abc');
  assert.equal(u.searchParams.get('select'), 'id,status');
});
