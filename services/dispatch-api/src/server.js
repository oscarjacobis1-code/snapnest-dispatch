import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createBooking, expireOffers, publicState, respondToOffer, setDriverStatus, setNightMode, subscribe } from './store.js';
import { parseStructuredTaxiRequest } from './dispatch-engine.js';

const here = fileURLToPath(new URL('.', import.meta.url));
const publicDir = normalize(join(here, '../../../apps/control-center/public'));
const port = Number(process.env.PORT || 8787);

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
    if (raw.length > 1_000_000) throw new Error('Request too large.');
  }
  if (!raw) return {};
  return JSON.parse(raw);
}

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json; charset=utf-8'
};

async function serveStatic(req, res, pathname) {
  const target = pathname === '/' ? '/index.html' : pathname;
  const safe = normalize(target).replace(/^([.][.][/\\])+/, '');
  const full = join(publicDir, safe);
  if (!full.startsWith(publicDir)) return false;
  try {
    const data = await readFile(full);
    res.writeHead(200, { 'content-type': mime[extname(full)] || 'application/octet-stream' });
    res.end(data);
    return true;
  } catch {
    return false;
  }
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const path = url.pathname;

    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'access-control-allow-origin': '*',
        'access-control-allow-methods': 'GET,POST,OPTIONS',
        'access-control-allow-headers': 'content-type'
      });
      return res.end();
    }

    if (req.method === 'GET' && path === '/api/state') return json(res, 200, publicState());

    if (req.method === 'GET' && path === '/api/events') {
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
        'access-control-allow-origin': '*'
      });
      res.write(`event: snapshot\ndata: ${JSON.stringify(publicState())}\n\n`);
      const unsubscribe = subscribe((event) => res.write(`event: update\ndata: ${JSON.stringify(event)}\n\n`));
      req.on('close', unsubscribe);
      return;
    }

    if (req.method === 'POST' && path === '/api/bookings') {
      const payload = parseStructuredTaxiRequest(await body(req));
      return json(res, 201, createBooking({ ...payload, source: 'web' }));
    }

    if (req.method === 'POST' && path === '/api/whatsapp/inbound') {
      const input = await body(req);
      // v0.1 adapter accepts already-structured intent. Production adapter will
      // verify Meta signatures, persist the conversation and parse text/voice/location.
      const payload = parseStructuredTaxiRequest(input);
      return json(res, 201, createBooking({ ...payload, source: 'whatsapp' }));
    }

    if (req.method === 'POST' && path === '/api/night-mode') {
      const input = await body(req);
      return json(res, 200, { nightMode: setNightMode(input.enabled) });
    }

    const statusMatch = path.match(/^\/api\/drivers\/([^/]+)\/status$/);
    if (req.method === 'POST' && statusMatch) {
      const input = await body(req);
      return json(res, 200, setDriverStatus(statusMatch[1], input.status));
    }

    const responseMatch = path.match(/^\/api\/bookings\/([^/]+)\/offer-response$/);
    if (req.method === 'POST' && responseMatch) {
      const input = await body(req);
      return json(res, 200, respondToOffer({ bookingId: responseMatch[1], driverId: input.driverId, accept: Boolean(input.accept) }));
    }

    if (req.method === 'GET' && await serveStatic(req, res, path)) return;
    return json(res, 404, { error: 'Not found' });
  } catch (error) {
    return json(res, 400, { error: error.message || 'Bad request' });
  }
});

setInterval(() => expireOffers(), 1000).unref();
server.listen(port, () => console.log(`SnapNest Dispatch running on http://localhost:${port}`));
