export function runtimeConfig(env = process.env) {
  const supabaseUrl = String(env.SUPABASE_URL ?? '').replace(/\/$/, '');
  const supabaseSecretKey = String(env.SUPABASE_SECRET_KEY ?? '');
  const supabasePublishableKey = String(env.SUPABASE_PUBLISHABLE_KEY ?? '');
  const tenantSlug = String(env.SUPABASE_TENANT_SLUG ?? 'demo-base');
  const persistent = Boolean(supabaseUrl && supabaseSecretKey && supabasePublishableKey);
  const production = String(env.NODE_ENV ?? '').toLowerCase() === 'production';

  if (production && !persistent) {
    const missing = [
      ['SUPABASE_URL', supabaseUrl],
      ['SUPABASE_SECRET_KEY', supabaseSecretKey],
      ['SUPABASE_PUBLISHABLE_KEY', supabasePublishableKey]
    ].filter(([, value]) => !value).map(([key]) => key);
    throw new Error(`Production configuration incomplete: missing ${missing.join(', ')}`);
  }
  if (production && String(env.DISPATCH_REQUIRE_AUTH ?? 'true').toLowerCase() === 'false') {
    throw new Error('Production cannot disable dispatch authentication.');
  }

  return {
    supabaseUrl,
    supabaseSecretKey,
    supabasePublishableKey,
    tenantSlug,
    persistent,
    production,
    requireAuth: persistent && String(env.DISPATCH_REQUIRE_AUTH ?? 'true').toLowerCase() !== 'false'
  };
}
