import { refreshAuthSession } from '/supabase-client.js';

export function accessToken() { return localStorage.getItem('dispatch_access_token') || ''; }
export function refreshToken() { return localStorage.getItem('dispatch_refresh_token') || ''; }
export function membership() { try { return JSON.parse(localStorage.getItem('dispatch_membership') || 'null'); } catch { return null; } }
export function currentUser() { try { return JSON.parse(localStorage.getItem('dispatch_user') || 'null'); } catch { return null; } }
export function currentDriver() { try { return JSON.parse(localStorage.getItem('dispatch_driver') || 'null'); } catch { return null; } }

export function saveSession(session) {
  if (session.accessToken) localStorage.setItem('dispatch_access_token', session.accessToken);
  if (session.refreshToken) localStorage.setItem('dispatch_refresh_token', session.refreshToken);
  if (session.user) localStorage.setItem('dispatch_user', JSON.stringify(session.user));
  if (session.membership) localStorage.setItem('dispatch_membership', JSON.stringify(session.membership));
  if (session.driver) localStorage.setItem('dispatch_driver', JSON.stringify(session.driver));
  else localStorage.removeItem('dispatch_driver');
}

export function clearSession() {
  ['dispatch_access_token','dispatch_refresh_token','dispatch_user','dispatch_membership','dispatch_driver'].forEach((k)=>localStorage.removeItem(k));
}

async function refreshStoredSession() {
  const token = refreshToken();
  if (!token) throw new Error('No refresh token');
  const session = await refreshAuthSession(token);
  saveSession(session);
  return session.accessToken;
}

async function request(path, options, token) {
  const headers = {
    ...(options.body ? { 'content-type': 'application/json' } : {}),
    ...(token ? { authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {})
  };
  return fetch(path, { ...options, headers });
}

export async function api(path, options = {}) {
  let token = accessToken();
  if (!token) {
    clearSession();
    if (!location.pathname.endsWith('/login.html')) location.href = `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`;
    throw new Error('Sign in required');
  }

  let response = await request(path, options, token);
  if (response.status === 401) {
    try {
      token = await refreshStoredSession();
      response = await request(path, options, token);
    } catch {
      clearSession();
      if (!location.pathname.endsWith('/login.html')) location.href = `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`;
      throw new Error('Sign in required');
    }
  }

  let data = null;
  try { data = await response.json(); } catch {}
  if (!response.ok) throw new Error(data?.error || 'Request failed');
  return data;
}
