const $ = (s) => document.querySelector(s);
const escapeHtml = (s='') => String(s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

async function api(path, options={}) {
  const response = await fetch(path, { headers: { 'content-type': 'application/json', ...(options.headers||{}) }, ...options });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'Request failed');
  return data;
}

function percentLocation(driver) {
  const latMin = 6.798, latMax = 6.836, lngMin = -58.177, lngMax = -58.135;
  const x = ((driver.location.lng - lngMin) / (lngMax - lngMin)) * 100;
  const y = 100 - ((driver.location.lat - latMin) / (latMax - latMin)) * 100;
  return { x: Math.max(5, Math.min(95, x)), y: Math.max(8, Math.min(92, y)) };
}

function render(state) {
  const available = state.drivers.filter(d=>d.status==='available').length;
  const busy = state.drivers.filter(d=>d.status==='busy').length;
  const open = state.bookings.filter(b=>['pending','offering'].includes(b.status)).length;
  const assigned = state.bookings.filter(b=>['assigned','in_progress','completed'].includes(b.status)).length;
  $('#stats').innerHTML = `
    <div class="card stat"><span class="muted">Available cars</span><strong>${available}</strong></div>
    <div class="card stat"><span class="muted">Busy cars</span><strong>${busy}</strong></div>
    <div class="card stat"><span class="muted">Open requests</span><strong>${open}</strong></div>
    <div class="card stat"><span class="muted">Assigned today</span><strong>${assigned}</strong></div>`;

  $('#nightMode').textContent = state.nightMode ? 'Night automation ON' : 'Night automation OFF';
  $('#nightMode').className = `btn ${state.nightMode ? 'success' : 'secondary'}`;

  $('#map').querySelectorAll('.pin').forEach(el=>el.remove());
  state.drivers.forEach(d => {
    if(!d.location) return;
    const p=percentLocation(d);
    const el=document.createElement('div');
    el.className=`pin ${d.status}`;
    el.style.left=`${p.x}%`; el.style.top=`${p.y}%`;
    el.textContent=`${d.name} · ${d.status}`;
    $('#map').appendChild(el);
  });

  $('#driversBody').innerHTML = state.drivers.map(d=>`<tr>
    <td><strong>${escapeHtml(d.name)}</strong><div class="muted">${escapeHtml(d.vehicle)}</div></td>
    <td><span class="badge ${d.status}"><i class="dot"></i>${escapeHtml(d.status)}</span></td>
    <td>${d.queueRank ?? '—'}</td>
    <td>${d.recentDeclines ?? 0}</td>
  </tr>`).join('');

  $('#bookings').innerHTML = state.bookings.length ? state.bookings.map(b=>`<div class="offer">
    <div class="row" style="justify-content:space-between"><h3>${escapeHtml(b.id)}</h3><span class="badge">${escapeHtml(b.status)}</span></div>
    <div><strong>${escapeHtml(b.pickup.label)}</strong> → ${escapeHtml(b.destination.label)}</div>
    <div class="muted" style="margin-top:5px">${escapeHtml(b.passengerName)} · ${b.passengers} passenger${b.passengers===1?'':'s'} · ${escapeHtml(b.source)}</div>
    ${b.currentOfferDriverId ? `<div class="notice" style="margin-top:10px">Awaiting response from ${escapeHtml(b.currentOfferDriverId)}</div>`:''}
    ${b.assignedDriverId ? `<div class="notice" style="margin-top:10px">Assigned to ${escapeHtml(b.assignedDriverId)}</div>`:''}
  </div>`).join('') : '<div class="muted">No bookings yet. Open the customer simulator and create one.</div>';

  $('#events').innerHTML = state.events.map(e=>`<div class="event"><strong>${escapeHtml(e.type)}</strong><span class="muted">${new Date(e.at).toLocaleTimeString()}</span> · <span class="mono">${escapeHtml(JSON.stringify(e.payload))}</span></div>`).join('');
}

async function refresh(){ render(await api('/api/state')); }
$('#nightMode').onclick = async()=>{ const state=await api('/api/state'); await api('/api/night-mode',{method:'POST',body:JSON.stringify({enabled:!state.nightMode})}); await refresh(); };
const stream = new EventSource('/api/events');
stream.addEventListener('snapshot', e=>render(JSON.parse(e.data)));
stream.addEventListener('update', refresh);
refresh();
