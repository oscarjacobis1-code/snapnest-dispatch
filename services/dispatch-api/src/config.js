export function runtimeConfig(env = process.env) {
  const supabaseUrl = String(env.SUPABASE_URL ?? '').replace(/\/$/, '');
  const supabaseSecretKey = String(env.SUPABASE_SECRET_KEY ?? '');
  const supabasePublishableKey = String(env.SUPABASE_PUBLISHABLE_KEY ?? '');
  const tenantSlug = String(env.SUPABASE_TENANT_SLUG ?? 'demo-base');
  const persistent = Boolean(supabaseUrl && supabaseSecretKey && supabasePublishableKey);
  return {
    supabaseUrl,
    supabaseSecretKey,
    supabasePublishableKey,
    tenantSlug,
    persistent,
    requireAuth: persistent && String(env.DISPATCH_REQUIRE_AUTH ?? 'true').toLowerCase() !== 'false'
  };
}
