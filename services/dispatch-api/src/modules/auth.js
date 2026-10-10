const ALLOWED_PROVIDERS = new Set(['google', 'azure']);
const ALLOWED_REDIRECT_PREFIXES = [
  'snapnestdispatch://auth/',
  'https://snapnest-dispatch.onrender.com/'
];

function ensureConfigured(config) {
  if (!config?.supabaseUrl || !config?.supabasePublishableKey) {
    const error = new Error('Authentication provider is not configured.');
    error.status = 503;
    throw error;
  }
}

function cleanRedirect(value) {
  const redirect = String(value || 'snapnestdispatch://auth/callback').trim();
  if (!ALLOWED_REDIRECT_PREFIXES.some((prefix) => redirect.startsWith(prefix))) {
    const error = new Error('Invalid authentication redirect.');
    error.status = 400;
    throw error;
  }
  return redirect;
}

async function authJson(response, fallback) {
  const text = await response.text();
  let data = {};
  if (text) {
    try { data = JSON.parse(text); }
    catch { data = { message: text }; }
  }
  if (!response.ok) {
    const error = new Error(data?.msg || data?.error_description || data?.message || data?.error || fallback);
    error.status = response.status;
    throw error;
  }
  return data;
}

export function createAuthModule({ config, store, fetchImpl = fetch }) {
  ensureConfigured(config);

  function oauthUrl(provider, redirectTo) {
    const cleanProvider = String(provider || '').toLowerCase();
    if (!ALLOWED_PROVIDERS.has(cleanProvider)) {
      const error = new Error('Unsupported SSO provider.');
      error.status = 400;
      throw error;
    }
    const redirect = cleanRedirect(redirectTo);
    const url = new URL(`${config.supabaseUrl}/auth/v1/authorize`);
    url.searchParams.set('provider', cleanProvider);
    url.searchParams.set('redirect_to', redirect);
    if (cleanProvider === 'azure') url.searchParams.set('scopes', 'email');
    return url.toString();
  }

  async function requestPasswordReset(email, redirectTo) {
    const cleanEmail = String(email || '').trim().toLowerCase();
    if (!cleanEmail || !cleanEmail.includes('@')) {
      const error = new Error('Enter a valid email address.');
      error.status = 400;
      throw error;
    }
    const redirect = cleanRedirect(redirectTo);
    const response = await fetchImpl(`${config.supabaseUrl}/auth/v1/recover`, {
      method: 'POST',
      headers: {
        apikey: config.supabasePublishableKey,
        'content-type': 'application/json'
      },
      body: JSON.stringify({ email: cleanEmail, redirect_to: redirect })
    });
    await authJson(response, 'Could not send reset email.');
    return { ok: true };
  }

  async function updatePassword(accessToken, password) {
    const token = String(accessToken || '').trim();
    const cleanPassword = String(password || '');
    if (!token) {
      const error = new Error('Recovery session is missing.');
      error.status = 401;
      throw error;
    }
    if (cleanPassword.length < 8) {
      const error = new Error('Password must be at least 8 characters.');
      error.status = 400;
      throw error;
    }
    const response = await fetchImpl(`${config.supabaseUrl}/auth/v1/user`, {
      method: 'PUT',
      headers: {
        apikey: config.supabasePublishableKey,
        authorization: `Bearer ${token}`,
        'content-type': 'application/json'
      },
      body: JSON.stringify({ password: cleanPassword })
    });
    await authJson(response, 'Could not update password.');
    return { ok: true };
  }

  async function sessionFromTokens(accessToken, refreshToken, expiresIn = null) {
    const access = String(accessToken || '').trim();
    const refresh = String(refreshToken || '').trim();
    if (!access || !refresh) {
      const error = new Error('Incomplete SSO session.');
      error.status = 400;
      throw error;
    }
    const context = await store.sessionContext(access);
    return {
      accessToken: access,
      refreshToken: refresh,
      expiresIn: Number(expiresIn || 3600),
      ...context
    };
  }

  return {
    oauthUrl,
    requestPasswordReset,
    updatePassword,
    sessionFromTokens
  };
}
