export function accessToken() { return localStorage.getItem('dispatch_access_token') || ''; }
export function refreshToken() { return localStorage.getItem('dispatch_refresh_token') || ''; }
export function saveSession(session) {
  if (session.accessToken) localStorage.setItem('dispatch_access_token', session.accessToken);
  if (session.refreshToken) localStorage.setItem('dispatch_refresh_token', session.refreshToken);
  if (session.user) localStorage.setItem('dispatch_user', JSON.stringify(session.user));
  if (session.membership) localStorage.setItem('dispatch_membership', JSON.stringify(session.membership));
  if (session.driver) localStorage.setItem('dispatch_driver', JSON.stringify(session.driver));
}
export function clearSession() { ['dispatch_access_token','dispatch_refresh_token','dispatch_user','dispatch_membership','dispatch_driver'].forEach((k)=>localStorage.removeItem(k)); }
export async function api(path, options = {}) {
  const token = accessToken();
  const headers = { ...(options.body ? { 'content-type': 'application/json' } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}), ...(options.headers || {}) };
  const response = await fetch(path, { ...options, headers });
  let data = null;
  try { data = await response.json(); } catch {}
  if (response.status === 401 && path !== '/api/auth/login') {
    clearSession();
    if (!location.pathname.endsWith('/login.html')) location.href = `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`;
  }
  if (!response.ok) throw new Error(data?.error || 'Request failed');
  return data;
}
