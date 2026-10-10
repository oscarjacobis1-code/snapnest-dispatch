function ensureLink(rel, href, extra = {}) {
  let link = document.querySelector(`link[rel="${rel}"]`);
  if (!link) {
    link = document.createElement('link');
    link.rel = rel;
    document.head.appendChild(link);
  }
  link.href = href;
  Object.entries(extra).forEach(([key, value]) => link.setAttribute(key, value));
  return link;
}

function ensureMeta(name, content) {
  let meta = document.querySelector(`meta[name="${name}"]`);
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = name;
    document.head.appendChild(meta);
  }
  meta.content = content;
}

ensureLink('manifest', '/manifest.json');
ensureLink('stylesheet', '/tablet.css', { 'data-dispatch-tablet': 'true' });
ensureMeta('theme-color', '#0d2a40');
ensureMeta('mobile-web-app-capable', 'yes');
ensureMeta('apple-mobile-web-app-capable', 'yes');
ensureMeta('apple-mobile-web-app-status-bar-style', 'black-translucent');

const banner = document.createElement('div');
banner.className = 'dispatch-connection-banner';
banner.setAttribute('role', 'status');
banner.setAttribute('aria-live', 'polite');
banner.textContent = 'Offline · live dispatch is paused until this device reconnects';
document.body.appendChild(banner);

function syncConnectivity() {
  document.body.classList.toggle('dispatch-offline', !navigator.onLine);
}

window.addEventListener('online', syncConnectivity);
window.addEventListener('offline', syncConnectivity);
syncConnectivity();

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch((error) => {
      console.warn('Dispatch PWA registration failed', error);
    });
  }, { once: true });
}
