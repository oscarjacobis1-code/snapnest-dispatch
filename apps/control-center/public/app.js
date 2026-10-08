import { api, clearSession } from '/session.js';
const $ = (s) => document.querySelector(s);
const escapeHtml = (s='') => String(s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let lastState = null;

function percentLocation(driver) {
  const latMin = 6.798, latMax = 6.836, lngMin = -58.177, lngMax = -58.135;
  const x = ((driver.location.lng - lngMin) / (lngMax - lngMin)) * 100;
  const y = 100 - ((driver.location.lat - latMin) / (latMax - latMin)) * 100;
  return { x: Math.max(5, Math.min(95, x)), y: Math.max(8, Math.min(92, y)) };
}
function ago(value) { if(!value) return '—'; const s=Math.max(0,Math.round((Date.now()-new Date(value).getTime())/1000)); return s<60?`${s}s ago`:`${Math.round(s/60)}m ago`; }
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
  $('#map').querySelectorAll('.pin').forEach(el=>el.remove());
  state.drivers.forEach(d=>{if(!d.location)return;const p=percentLocation(d),el=document.createElement('div');el.className=`pin ${d.status}`;el.style.left=`${p.x}%`;el.style.top=`${p.y}%`;el.textContent=`${d.name} · ${d.status}`;$('#map').appendChild(el);});
  $('#driversBody').innerHTML=state.drivers.map(d=>`<tr><td><strong>${escapeHtml(d.name)}</strong><div class="muted">${escapeHtml(d.vehicle)}</div></td><td><span class="badge ${d.status}"><i class="dot"></i>${escapeHtml(d.status)}</span></td><td>${d.queueRank??'—'}</td><td>${d.recentDeclines??0}</td><td>${ago(d.location?.capturedAt||d.lastSeenAt)}</td></tr>`).join('');
  $('#bookings').innerHTML=state.bookings.length?state.bookings.map(b=>`<div class="offer"><div class="row" style="justify-content:space-between"><h3>${escapeHtml(String(b.id).slice(0,8))}</h3><span class="badge">${escapeHtml(b.status)}</span></div><div><strong>${escapeHtml(b.pickup.label)}</strong> → ${escapeHtml(b.destination.label)}</div><div class="muted" style="margin-top:5px">${escapeHtml(b.passengerName)} · ${b.passengers} passenger${b.passengers===1?'':'s'} · ${escapeHtml(b.source)}</div>${b.currentOfferDriverId?`<div class="notice" style="margin-top:10px">Awaiting driver response</div>`:''}${b.assignedDriverId?`<div class="notice" style="margin-top:10px">Driver assigned</div>`:''}</div>`).join(''):'<div class="muted">No bookings yet.</div>';
  $('#events').innerHTML=(state.events||[]).map(e=>`<div class="event"><strong>${escapeHtml(e.type)}</strong><span class="muted">${new Date(e.at).toLocaleTimeString()}</span> · <span class="mono">${escapeHtml(JSON.stringify(e.payload))}</span></div>`).join('')||'<div class="muted">No recent activity.</div>';
}
async function refresh(){try{render(await api('/api/state'));}catch(err){if(!String(err.message).includes('Sign in')) console.error(err);}}
$('#nightMode').onclick=async()=>{await api('/api/night-mode',{method:'POST',body:JSON.stringify({enabled:!lastState?.nightMode})});await refresh();};
$('#logout').onclick=()=>{clearSession();location.href='/login.html';};
await refresh();setInterval(refresh,1500);
