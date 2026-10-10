import { api } from '/session.js';
import { renderSafetyBanner } from '/modules/people-support-ui.js';

const style = document.createElement('style');
style.textContent = `
  .safety-banner{margin:0 0 14px 0}
  .safety-banner[hidden]{display:none!important}
  .safety-banner-button{width:100%;border:1px solid rgba(239,90,103,.34);background:rgba(82,19,28,.92);color:#fff;border-radius:14px;padding:11px 14px;display:flex;align-items:center;gap:11px;text-align:left;cursor:pointer;box-shadow:0 12px 30px rgba(0,0,0,.16)}
  .safety-banner-button:hover{background:rgba(99,23,34,.96)}
  .safety-pulse{width:9px;height:9px;border-radius:999px;background:#ef5a67;box-shadow:0 0 0 0 rgba(239,90,103,.55);animation:snapnestSafetyPulse 1.7s infinite}
  .safety-banner-copy{display:flex;flex-direction:column;gap:2px;min-width:0;flex:1}
  .safety-banner-copy strong{font-size:12px;font-weight:700;letter-spacing:.01em}
  .safety-banner-copy small{font-size:10px;color:rgba(255,255,255,.72);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .safety-banner-action{font-size:10px;font-weight:700;color:#ffd9dd;white-space:nowrap}
  .support-row.safety-ticket{background:rgba(239,90,103,.07);border-color:rgba(239,90,103,.22)}
  @keyframes snapnestSafetyPulse{0%{box-shadow:0 0 0 0 rgba(239,90,103,.55)}70%{box-shadow:0 0 0 9px rgba(239,90,103,0)}100%{box-shadow:0 0 0 0 rgba(239,90,103,0)}}
`;
document.head.appendChild(style);

const main = document.querySelector('.dispatch-main');
const topbar = document.querySelector('.dispatch-topbar');
const banner = document.createElement('div');
banner.id = 'safetyBanner';
banner.className = 'safety-banner';
banner.hidden = true;
if (main && topbar) topbar.insertAdjacentElement('afterend', banner);

banner.addEventListener('click', () => {
  document.querySelector('.dispatch-nav-item[data-page-target="support"]')?.click();
});

async function refreshSafety() {
  if (!banner) return;
  try {
    const result = await api('/api/support?limit=100');
    renderSafetyBanner(banner, result.tickets || []);
  } catch (error) {
    if (!String(error?.message || '').includes('Sign in')) console.warn('Safety watch unavailable', error);
  }
}

await refreshSafety();
setInterval(refreshSafety, 5000);
