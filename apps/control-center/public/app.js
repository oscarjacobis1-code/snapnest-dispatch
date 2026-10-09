import * as maplibregl from 'https://unpkg.com/maplibre-gl@6.13.0/dist/maplibre-gl.mjs';
import { api, clearSession, membership } from '/session.js';

const $ = (s) => document.querySelector(s);
const escapeHtml = (s='') => String(s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let lastState = null;
const member = membership();
if (member?.role === 'admin') $('#adminLink').style.display = 'inline-flex';

const map = new maplibregl.Map({
  container: 'map',
  style: 'https://tiles.openfreemap.org/styles/liberty',
  center: [-58.155, 6.812],
  zoom: 12.7,
  attributionControl: true
});
map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'top-right');
const driverMarkers = new Map();
let fittedFleetOnce = false;

function ago(value) {
  if(!value) return '—';
  const s=Math.max(0,Math.round((Date.now()-new Date(value).getTime())/1000));
  return s<60?`${s}s ago`:`${Math.round(s/60)}m ago`;
}

function updateFleetMap(drivers) {
  const liveIds = new Set();
  const bounds = new maplibregl.LngLatBounds();
  let positioned = 0;

  drivers.forEach((driver) => {
    if (!driver.location || !Number.isFinite(Number(driver.location.lat)) || !Number.isFinite(Number(driver.location.lng))) return;
    liveIds.add(driver.id);
    positioned += 1;
    const point = [Number(driver.location.lng), Number(driver.location.lat)];
    bounds.extend(point);

    let record = driverMarkers.get(driver.id);
    if (!record) {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'vehicle-marker';
      el.setAttribute('aria-label', `${driver.name} vehicle location`);
      const marker = new maplibregl.Marker({ element: el, anchor: 'center' })
        .setLngLat(point)
        .setPopup(new maplibregl.Popup({ offset: 22, closeButton: false }));
      marker.addTo(map);
      record = { marker, el };
      driverMarkers.set(driver.id, record);
    }

    record.marker.setLngLat(point);
    record.el.className = `vehicle-marker ${driver.status}`;
    record.el.textContent = String(driver.name || 'Car').replace(/^Car\s*/i, '') || '•';
    record.marker.getPopup().setHTML(
      `<strong>${escapeHtml(driver.name)}</strong><br>${escapeHtml(driver.vehicle || '')}<br><span style="text-transform:capitalize">${escapeHtml(driver.status)}</span><br><small>GPS ${escapeHtml(ago(driver.location.capturedAt || driver.lastSeenAt))}</small>`
    );
  });

  for (const [id, record] of driverMarkers) {
    if (!liveIds.has(id)) {
      record.marker.remove();
      driverMarkers.delete(id);
    }
  }

  if (!fittedFleetOnce && positioned > 0 && map.loaded()) {
    fittedFleetOnce = true;
    if (positioned === 1) map.easeTo({ center: bounds.getCenter(), zoom: 14 });
    else map.fitBounds(bounds, { padding: 70, maxZoom: 14, duration: 0 });
  }
}

function render(state) {
  lastState=state;
  if(state.tenant?.name) $('#baseName').textContent=`Control Center · ${state.tenant.name}`;
  const available = state.drivers.filter(d=>d.status==='available').length;
  const busy = state.drivers.filter(d=>d.status==='busy').length;
  const open = state.bookings.filter(b=>['pending','offering'].includes(b.status)).length;
  const assigned = state.bookings.filter(b=>['assigned','in_progress','completed'].includes(b.status)).length;
  $('#stats').innerHTML = `<div class="card stat"><span class="muted">Available cars</span><strong>${available}</strong></div><div class="card stat"><span class="muted">Busy cars</span><strong>${busy}</strong></div><div class="card stat"><span class="muted">Open requests</span><strong>${open}</strong></div><div class="card stat"><span class="muted">Assigned</span><strong>${assigned}</strong></div>`;
  $('#nightMode').textContent = state.nightMode ? 'Night automation ON' : 'Night automation OFF';
  $('#nightMode').className = `btn ${state.nightMode ? 'success' : 'secondary'}`;
  updateFleetMap(state.drivers);
  $('#driversBody').innerHTML=state.drivers.map(d=>`<tr><td><strong>${escapeHtml(d.name)}</strong><div class="muted">${escapeHtml(d.vehicle)}</div></td><td><span class="badge ${d.status}"><i class="dot"></i>${escapeHtml(d.status)}</span></td><td>${d.queueRank??'—'}</td><td>${d.recentDeclines??0}</td><td>${ago(d.location?.capturedAt||d.lastSeenAt)}</td></tr>`).join('');
  $('#bookings').innerHTML=state.bookings.length?state.bookings.map(b=>`<div class="offer"><div class="row" style="justify-content:space-between"><h3>${escapeHtml(String(b.id).slice(0,8))}</h3><span class="badge">${escapeHtml(b.status)}</span></div><div><strong>${escapeHtml(b.pickup.label)}</strong> → ${escapeHtml(b.destination.label)}</div><div class="muted" style="margin-top:5px">${escapeHtml(b.passengerName)} · ${b.passengers} passenger${b.passengers===1?'':'s'} · ${escapeHtml(b.source)}</div>${b.currentOfferDriverId?`<div class="notice" style="margin-top:10px">Awaiting driver response</div>`:''}${b.assignedDriverId?`<div class="notice" style="margin-top:10px">Driver assigned</div>`:''}</div>`).join(''):'<div class="muted">No bookings yet.</div>';
  $('#events').innerHTML=(state.events||[]).map(e=>`<div class="event"><strong>${escapeHtml(e.type)}</strong><span class="muted">${new Date(e.at).toLocaleTimeString()}</span> · <span class="mono">${escapeHtml(JSON.stringify(e.payload))}</span></div>`).join('')||'<div class="muted">No recent activity.</div>';
}

map.on('load', () => {
  if (lastState) updateFleetMap(lastState.drivers);
});

async function refresh(){try{render(await api('/api/state'));}catch(err){if(!String(err.message).includes('Sign in')) console.error(err);}}
$('#nightMode').onclick=async()=>{await api('/api/night-mode',{method:'POST',body:JSON.stringify({enabled:!lastState?.nightMode})});await refresh();};
$('#logout').onclick=()=>{clearSession();location.href='/login.html';};
await refresh();setInterval(refresh,1500);
