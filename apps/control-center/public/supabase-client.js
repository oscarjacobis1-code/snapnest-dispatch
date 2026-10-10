const SUPABASE_URL = 'https://bihtmrtbsombubfsdbjd.supabase.co';
const SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_lD1QorqGwPWdZ2Xhj4APkg_mn4Dm-IC';

async function parse(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.msg || data?.message || data?.error_description || data?.error || 'Request failed');
  return data;
}

async function memberContext(accessToken, userId) {
  const headers = {
    apikey: SUPABASE_PUBLISHABLE_KEY,
    authorization: `Bearer ${accessToken}`,
    accept: 'application/json'
  };
  const memberships = await parse(await fetch(`${SUPABASE_URL}/rest/v1/memberships?select=tenant_id,role&user_id=eq.${encodeURIComponent(userId)}&limit=1`, { headers }));
  const membership = memberships?.[0] || null;
  if (!membership) throw new Error('This account has not been assigned to a taxi base yet.');
  let driver = null;
  if (membership.role === 'driver') {
    const drivers = await parse(await fetch(`${SUPABASE_URL}/rest/v1/drivers?select=id,tenant_id,display_name,vehicle_plate,status&auth_user_id=eq.${encodeURIComponent(userId)}&limit=1`, { headers }));
    driver = drivers?.[0] || null;
    if (!driver) throw new Error('This driver account is not linked to a driver profile.');
  }
  return { membership, driver };
}

export async function signIn(email, password) {
  const auth = await parse(await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { apikey: SUPABASE_PUBLISHABLE_KEY, 'content-type': 'application/json' },
    body: JSON.stringify({ email, password })
  }));
  const { membership, driver } = await memberContext(auth.access_token, auth.user.id);
  return {
    accessToken: auth.access_token,
    refreshToken: auth.refresh_token,
    user: auth.user,
    membership,
    driver
  };
}

export async function refreshAuthSession(refreshToken) {
  if (!refreshToken) throw new Error('No refresh token');
  const auth = await parse(await fetch(`${SUPABASE_URL}/auth/v1/token?grant_type=refresh_token`, {
    method: 'POST',
    headers: { apikey: SUPABASE_PUBLISHABLE_KEY, 'content-type': 'application/json' },
    body: JSON.stringify({ refresh_token: refreshToken })
  }));
  const { membership, driver } = await memberContext(auth.access_token, auth.user.id);
  return {
    accessToken: auth.access_token,
    refreshToken: auth.refresh_token,
    user: auth.user,
    membership,
    driver
  };
}

export async function createOperator(accessToken, payload) {
  return parse(await fetch(`${SUPABASE_URL}/functions/v1/admin-users`, {
    method: 'POST',
    headers: {
      apikey: SUPABASE_PUBLISHABLE_KEY,
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify(payload)
  }));
}
