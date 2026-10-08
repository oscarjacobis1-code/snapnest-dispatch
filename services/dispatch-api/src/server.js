import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseStructuredTaxiRequest } from './dispatch-engine.js';
import { createRuntimeStore } from './runtime-store.js';

const here = fileURLToPath(new URL('.', import.meta.url));
const publicDir = normalize(join(here, '../../../apps/control-center/public'));
const port = Number(process.env.PORT || 8787);
const { store, config } = createRuntimeStore();

class HttpError extends Error { constructor(status, message) { super(message); this.status = status; } }

function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(data),
    'access-control-allow-origin': '*'
  });
  res.end(data);
}

async function body(req) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 1_000_000) throw new HttpError(413, 'Request too large.');
  }
  if (!raw) return {};
  try { return JSON.parse(raw); }
  catch { throw new HttpError(400, 'Invalid JSON body.'); }
}

const mime = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.svg': 'image/svg+xml', '.json': 'application/json; charset=utf-8' };
async function serveStatic(res, pathname) {
  const target = pathname === '/' ? '/index.html' : pathname;
  const safe = normalize(target).replace(/^([.][.][/\\])+/, '');
  const full = join(publicDir, safe);
  if (!full.startsWith(publicDir)) return false;
  try { const data = await readFile(full); res.writeHead(200, { 'content-type': mime[extname(full)] || 'application/octet-stream' }); res.end(data); return true; }
  catch { return false; }
}

function bearer(req) {
  const value = String(req.headers.authorization ?? '');
  return value.toLowerCase().startsWith('bearer ') ? value.slice(7).trim() : '';
}

async function authContext(req) {
  if (!config.requireAuth) return { user: { id: 'demo', email: 'demo@snapnest.local' }, membership: { role: 'admin' }, driver: null };
  const token = bearer(req);
  if (!token) throw new HttpError(401, 'Sign in required.');
  try { return await store.sessionContext(token); }
  catch (error) { throw new HttpError(401, error.message || 'Invalid session.'); }
}

function requireRole(context, roles) {
  if (!roles.includes(context.membership?.role)) throw new HttpError(403, 'You do not have permission for this action.');
}

function requireDriverAccess(context, driverId) {
  if (['admin', 'dispatcher'].includes(context.membership?.role)) return;
  if (context.membership?.role === 'driver' && context.driver?.id === driverId) return;
  throw new HttpError(403, 'You can only update your own driver session.');
}

function filterState(state, context) {
  if (context.membership?.role !== 'driver' || !context.driver?.id) return state;
  const driverId = context.driver.id;
  return {
    ...state,
    drivers: state.drivers.filter((d) => d.id === driverId),
    bookings: state.bookings.filter((b) => b.currentOfferDriverId === driverId || b.assignedDriverId === driverId),
    events: []
  };
}

const bookingRate = new Map();
function checkBookingRate(req) {
  const key = String(req.headers['x-forwarded-for'] ?? req.socket.remoteAddress ?? 'unknown').split(',')[0].trim();
  const now = Date.now();
  const current = bookingRate.get(key) ?? { start: now, count: 0 };
  if (now - current.start > 60_000) { current.start = now; current.count = 0; }
  current.count += 1; bookingRate.set(key, current);
  if (current.count > 20) throw new HttpError(429, 'Too many booking requests. Try again shortly.');
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const path = url.pathname;

    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'access-control-allow-origin': '*',
        'access-control-allow-methods': 'GET,POST,OPTIONS',
        'access-control-allow-headers': 'content-type,authorization,x-snapnest-webhook-key'
      });
      return res.end();
    }

    if (req.method === 'GET' && path === '/api/health') return json(res, 200, { ok: true, version: '0.2.0', mode: store.mode });

    if (req.method === 'POST' && path === '/api/auth/login') {
      const input = await body(req);
      if (!input.email || !input.password) throw new HttpError(400, 'Email and password are required.');
      return json(res, 200, await store.login(String(input.email).trim(), String(input.password)));
    }

    if (req.method === 'POST' && path === '/api/auth/refresh') {
      const input = await body(req);
      if (!input.refreshToken) throw new HttpError(400, 'Refresh token is required.');
      return json(res, 200, await store.refresh(input.refreshToken));
    }

    if (req.method === 'GET' && path === '/api/auth/me') return json(res, 200, await authContext(req));

    if (req.method === 'GET' && path === '/api/state') {
      const context = await authContext(req);
      return json(res, 200, filterState(await store.publicState(), context));
    }

    if (req.method === 'GET' && path === '/api/events') {
      const context = await authContext(req);
      if (store.mode !== 'memory') throw new HttpError(410, 'Realtime SSE is disabled in persistent mode; clients should poll /api/state.');
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive', 'access-control-allow-origin': '*' });
      res.write(`event: snapshot\ndata: ${JSON.stringify(filterState(await store.publicState(), context))}\n\n`);
      const unsubscribe = store.subscribe((event) => res.write(`event: update\ndata: ${JSON.stringify(event)}\n\n`));
      req.on('close', unsubscribe); return;
    }

    if (req.method === 'POST' && path === '/api/bookings') {
      checkBookingRate(req);
      const payload = parseStructuredTaxiRequest(await body(req));
      return json(res, 201, await store.createBooking({ ...payload, source: 'web' }));
    }

    if (req.method === 'POST' && path === '/api/whatsapp/inbound') {
      if (store.mode === 'supabase') {
        const expected = String(process.env.WHATSAPP_ADAPTER_KEY ?? '');
        if (!expected) throw new HttpError(503, 'WhatsApp adapter is not configured.');
        if (req.headers['x-snapnest-webhook-key'] !== expected) throw new HttpError(401, 'Invalid WhatsApp adapter key.');
      }
      const payload = parseStructuredTaxiRequest(await body(req));
      return json(res, 201, await store.createBooking({ ...payload, source: 'whatsapp' }));
    }

    if (req.method === 'POST' && path === '/api/night-mode') {
      const context = await authContext(req); requireRole(context, ['admin', 'dispatcher']);
      const input = await body(req); return json(res, 200, { nightMode: await store.setNightMode(input.enabled) });
    }

    const locationMatch = path.match(/^\/api\/drivers\/([^/]+)\/location$/);
    if (req.method === 'POST' && locationMatch) {
      const context = await authContext(req); requireDriverAccess(context, locationMatch[1]);
      return json(res, 200, await store.updateDriverLocation(locationMatch[1], await body(req)));
    }

    const statusMatch = path.match(/^\/api\/drivers\/([^/]+)\/status$/);
    if (req.method === 'POST' && statusMatch) {
      const context = await authContext(req); requireDriverAccess(context, statusMatch[1]);
      const input = await body(req); return json(res, 200, await store.setDriverStatus(statusMatch[1], input.status));
    }

    const responseMatch = path.match(/^\/api\/bookings\/([^/]+)\/offer-response$/);
    if (req.method === 'POST' && responseMatch) {
      const context = await authContext(req); const input = await body(req); requireDriverAccess(context, input.driverId);
      return json(res, 200, await store.respondToOffer({ bookingId: responseMatch[1], driverId: input.driverId, accept: Boolean(input.accept) }));
    }

    if (req.method === 'GET' && await serveStatic(res, path)) return;
    return json(res, 404, { error: 'Not found' });
  } catch (error) {
    const status = Number(error.status || 400);
    return json(res, status, { error: error.message || 'Request failed' });
  }
});

setInterval(() => store.expireOffers().catch?.((error) => console.error('expireOffers:', error.message)), 1000).unref();
server.listen(port, () => console.log(`SnapNest Dispatch v0.2 running on http://localhost:${port} (${store.mode})`));
