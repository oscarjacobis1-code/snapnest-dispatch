const CACHE = 'snapnest-dispatch-shell-v13';
const SHELL = [
  '/',
  '/index.html',
  '/login.html',
  '/styles.css',
  '/dispatcher-v2.css',
  '/tablet.css',
  '/app.js',
  '/session.js',
  '/supabase-client.js',
  '/ptt-web.js',
  '/safety-watch.js',
  '/reservations-ui.js',
  '/operator-shift-ui.js',
  '/live-trip-ui.js',
  '/pwa.js',
  '/manifest.json',
  '/modules/format.js',
  '/modules/bookings-ui.js',
  '/modules/fleet-ui.js',
  '/modules/people-support-ui.js'
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key.startsWith('snapnest-dispatch-shell-') && key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Never cache authenticated or live operational data.
  if (url.pathname.startsWith('/api/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(async () => (await caches.match(request)) || (await caches.match('/index.html')))
    );
    return;
  }

  const isStatic = /\.(?:css|js|json|html)$/i.test(url.pathname);
  if (!isStatic) return;

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request).then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      }).catch(() => cached);
      return cached || network;
    })
  );
});
