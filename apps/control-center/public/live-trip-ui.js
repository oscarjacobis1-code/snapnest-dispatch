import { api } from '/session.js';
import { ago, escapeHtml, statusLabel } from '/modules/format.js';

function mount() {
  const overview = document.querySelector('[data-page="overview"]');
  const grid = overview?.querySelector('.dispatch-grid');
  if (!overview || !grid || document.querySelector('#liveTripPanel')) return;
  const panel = document.createElement('section');
  panel.id = 'liveTripPanel';
  panel.className = 'dispatch-panel live-trip-panel';
  panel.innerHTML = `<div class="panel-heading"><div><span>Live trips</span><strong>Trips on the road</strong></div><small id="liveTripCount">0 active</small></div><div id="liveTripList" class="live-trip-list"><div class="empty-state"><strong>No active trips</strong><span>Assigned and in-progress trips will appear here.</span></div></div>`;
  grid.insertAdjacentElement('afterend', panel);

  const style = document.createElement('style');
  style.id = 'liveTripStyles';
  style.textContent = `
    .live-trip-panel{margin-top:12px}.live-trip-list{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:8px}.live-trip-card{border:1px solid #e1e5e7;border-radius:10px;background:#fff;padding:12px;display:grid;gap:9px}.live-trip-head{display:flex;justify-content:space-between;gap:10px;align-items:flex-start}.live-trip-head>div{display:grid;gap:2px}.live-trip-head strong{font-size:11px}.live-trip-head span{font-size:9px;color:#89949a}.live-trip-route{display:grid;grid-template-columns:12px 1fr;gap:7px;align-items:start}.live-trip-route i{width:7px;height:7px;border:2px solid #ef6a00;border-radius:50%;margin-top:3px}.live-trip-route.destination i{border-color:#173f5f}.live-trip-route span{display:block;font-size:8px;text-transform:uppercase;letter-spacing:.08em;color:#929da3}.live-trip-route strong{font-size:10px;line-height:1.35}.live-trip-ops{display:grid;grid-template-columns:repeat(3,1fr);gap:5px}.live-trip-metric{background:#f5f7f8;border-radius:7px;padding:7px}.live-trip-metric span{display:block;font-size:7px;color:#8b969c;text-transform:uppercase;letter-spacing:.06em}.live-trip-metric strong{display:block;margin-top:2px;font-size:9px;color:#253944}.live-trip-footer{display:flex;align-items:center;justify-content:space-between;gap:9px;padding-top:2px}.live-trip-driver{display:grid;gap:1px;min-width:0}.live-trip-driver strong{font-size:10px;overflow:hidden;text-overflow:ellipsis}.live-trip-driver span{font-size:8px;color:#88949a}.live-trip-card.waiting{border-color:#ecd4a2}.live-trip-card.in_progress{border-color:#bbd6e6}
    @media(max-width:760px){.live-trip-list{grid-template-columns:1fr}.live-trip-panel{margin-top:10px}}
  `;
  document.head.appendChild(style);
}

function elapsedSince(value) {
  if (!value) return '—';
  const start = new Date(value).getTime();
  if (!Number.isFinite(start)) return '—';
  const minutes = Math.max(0, Math.floor((Date.now() - start) / 60000));
  const hours = Math.floor(minutes / 60);
  return hours ? `${hours}h ${minutes % 60}m` : `${minutes}m`;
}

function stageTime(booking) {
  if (booking.status === 'in_progress') return ['Trip time', elapsedSince(booking.startedAt)];
  if (booking.status === 'arrived') return ['Waiting', elapsedSince(booking.arrivedAt)];
  return ['Assigned', elapsedSince(booking.assignedAt)];
}

function driverFor(state, booking) {
  return state.drivers?.find((driver) => driver.id === booking.assignedDriverId) || null;
}

function render(state) {
  const list = document.querySelector('#liveTripList');
  const count = document.querySelector('#liveTripCount');
  if (!list || !count) return;
  const active = (state.bookings || [])
    .filter((booking) => ['assigned','arrived','in_progress'].includes(booking.status))
    .sort((a,b) => new Date(a.assignedAt || a.createdAt || 0) - new Date(b.assignedAt || b.createdAt || 0));
  count.textContent = `${active.length} active`;
  list.innerHTML = active.length ? active.map((booking) => {
    const driver = driverFor(state, booking);
    const [timeLabel, timeValue] = stageTime(booking);
    const gps = driver?.location?.capturedAt || driver?.lastSeenAt;
    const phone = String(booking.passengerPhone || '').trim();
    return `<article class="live-trip-card ${escapeHtml(booking.status)}">
      <div class="live-trip-head"><div><strong>${escapeHtml(booking.passengerName || 'Guest')}</strong><span>#${escapeHtml(String(booking.id).slice(0,8).toUpperCase())}${phone ? ` · ${escapeHtml(phone)}` : ''}</span></div><span class="badge ${escapeHtml(booking.status)}">${escapeHtml(statusLabel(booking.status))}</span></div>
      <div class="live-trip-route"><i></i><div><span>Pickup</span><strong>${escapeHtml(booking.pickup?.label || 'Pickup')}</strong></div></div>
      <div class="live-trip-route destination"><i></i><div><span>Destination</span><strong>${escapeHtml(booking.destination?.label || 'Destination')}</strong></div></div>
      <div class="live-trip-ops">
        <div class="live-trip-metric"><span>${escapeHtml(timeLabel)}</span><strong>${escapeHtml(timeValue)}</strong></div>
        <div class="live-trip-metric"><span>GPS</span><strong>${escapeHtml(gps ? ago(gps) : 'No fix')}</strong></div>
        <div class="live-trip-metric"><span>Reassigned</span><strong>${Number(booking.reassignCount || 0)}×</strong></div>
      </div>
      <div class="live-trip-footer"><div class="live-trip-driver"><strong>${escapeHtml(driver?.name || 'Driver')}</strong><span>${escapeHtml(driver?.vehicle || 'Vehicle not set')} · ${escapeHtml(driver?.status || 'unknown')}</span></div><button class="text-action" type="button" data-manage-booking="${escapeHtml(booking.id)}">Manage</button></div>
    </article>`;
  }).join('') : '<div class="empty-state"><strong>No active trips</strong><span>Assigned, waiting and in-progress trips will appear here.</span></div>';
}

async function refreshLiveTrips() {
  try { render(await api('/api/state')); }
  catch (error) { if (!String(error.message).includes('Sign in')) console.error('Live trip refresh failed', error); }
}

mount();
await refreshLiveTrips();
setInterval(refreshLiveTrips, 5000);
