import * as maplibregl from 'https://unpkg.com/maplibre-gl@6.13.0/dist/maplibre-gl.mjs';
import { api, clearSession, membership } from '/session.js';

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const escapeHtml = (s='') => String(s).replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const member = membership();
let lastState = null;
let bookingFilter = 'open';
let pinMode = null;
let pickupPin = null;
let destinationPin = null;
let pickupMarker = null;
let destinationMarker = null;
let fittedFleetOnce = false;
const driverMarkers = new Map();

if (member?.role === 'admin') $('#adminLink').style.display = 'flex';

const map = new maplibregl.Map({
  container: 'map',
  style: 'https://tiles.openfreemap.org/styles/liberty',
  center: [-58.155, 6.812],
  zoom: 12.7,
  attributionControl: false,
  dragRotate: false,
  pitchWithRotate: false
});
map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');

function ago(value) {
  if (!value) return '—';
  const ms = Date.now() - new Date(value).getTime();
  if (!Number.isFinite(ms)) return '—';
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  return `${Math.round(m / 60)}h ago`;
}

function idleFor(driver) {
  if (!driver.availableSince || driver.status !== 'available') return '';
  const m = Math.max(0, Math.round((Date.now() - Number(driver.availableSince)) / 60000));
  return m < 1 ? 'just available' : `${m}m idle`;
}

function driverName(id) {
  return lastState?.drivers?.find((d) => d.id === id)?.name || 'Driver';
}

function bookingBucket(status) {
  if (['pending', 'offering'].includes(status)) return 'open';
  if (['assigned', 'in_progress'].includes(status)) return 'active';
  return 'done';
}

function setMapMode(text = 'Live tracking', pin = false) {
  const el = $('#mapMode');
  if (!el) return;
  el.textContent = text;
  el.classList.toggle('pin-mode', pin);
}

function updateFleetMap(drivers) {
  const liveIds = new Set();
  const bounds = new maplibregl.LngLatBounds();
  let positioned = 0;

  drivers.forEach((driver) => {
    const lat = Number(driver.location?.lat);
    const lng = Number(driver.location?.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    liveIds.add(driver.id);
    positioned += 1;
    const point = [lng, lat];
    bounds.extend(point);

    let record = driverMarkers.get(driver.id);
    if (!record) {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'vehicle-marker';
      el.setAttribute('aria-label', `${driver.name} vehicle location`);
      const marker = new maplibregl.Marker({ element: el, anchor: 'center' })
        .setLngLat(point)
        .setPopup(new maplibregl.Popup({ offset: 21, closeButton: false }));
      marker.addTo(map);
      record = { marker, el };
      driverMarkers.set(driver.id, record);
    }

    const initials = String(driver.name || 'D').trim().split(/\s+/).map((v) => v[0]).join('').slice(0, 2).toUpperCase();
    record.marker.setLngLat(point);
    record.el.className = `vehicle-marker ${driver.status}`;
    record.el.textContent = initials || 'D';
    record.marker.getPopup().setHTML(
      `<strong>${escapeHtml(driver.name)}</strong><br>${escapeHtml(driver.vehicle || 'Vehicle not set')}<br><span style="text-transform:capitalize">${escapeHtml(driver.status)}</span>${idleFor(driver) ? `<br><small>${escapeHtml(idleFor(driver))}</small>` : ''}<br><small>GPS ${escapeHtml(ago(driver.location?.capturedAt || driver.lastSeenAt))}</small>`
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
    else map.fitBounds(bounds, { padding: 65, maxZoom: 14, duration: 0 });
  }
}

function renderStats(state) {
  const available = state.drivers.filter((d) => d.status === 'available').length;
  const busy = state.drivers.filter((d) => d.status === 'busy').length;
  const open = state.bookings.filter((b) => ['pending', 'offering'].includes(b.status)).length;
  const active = state.bookings.filter((b) => ['assigned', 'in_progress'].includes(b.status)).length;
  $('#stats').innerHTML = [
    ['Available cars', available, 'available', `${state.drivers.length} total drivers`],
    ['Busy cars', busy, 'busy', 'Assigned or on trip'],
    ['Waiting', open, 'open', 'Needs a driver'],
    ['Active trips', active, 'active', 'Pickup + in progress']
  ].map(([label, value, kind, note]) => `<div class="dispatch-stat"><div class="dispatch-stat-top"><span class="dispatch-stat-label">${label}</span><i class="dispatch-stat-dot ${kind}"></i></div><strong>${value}</strong><small>${note}</small></div>`).join('');
}

function renderDrivers(state) {
  const drivers = [...state.drivers].sort((a, b) => {
    const order = { available: 0, offered: 1, busy: 2, unavailable: 3, offline: 4 };
    return (order[a.status] ?? 9) - (order[b.status] ?? 9) || Number(a.queueRank ?? 999) - Number(b.queueRank ?? 999);
  });
  $('#driversBody').innerHTML = drivers.map((d) => `<tr>
    <td><strong>${escapeHtml(d.name)}</strong><div class="muted">${escapeHtml(d.vehicle || 'Vehicle not set')}${idleFor(d) ? ` · ${escapeHtml(idleFor(d))}` : ''}</div></td>
    <td><span class="badge ${escapeHtml(d.status)}"><i class="dot"></i>${escapeHtml(d.status)}</span></td>
    <td>${d.queueRank ?? '—'}</td>
    <td>${d.recentDeclines ?? 0}</td>
    <td>${escapeHtml(ago(d.location?.capturedAt || d.lastSeenAt))}</td>
  </tr>`).join('') || '<tr><td colspan="5" class="muted">No drivers found.</td></tr>';
}

function bookingCard(b) {
  const driver = b.assignedDriverId ? driverName(b.assignedDriverId) : b.currentOfferDriverId ? driverName(b.currentOfferDriverId) : '';
  const statusCopy = b.status === 'offering' ? `Offer sent to ${driver}` : b.status === 'assigned' ? `${driver} heading to pickup` : b.status === 'in_progress' ? `${driver} on trip` : b.status;
  return `<article class="dispatch-booking">
    <div class="dispatch-booking-head"><span class="booking-id">#${escapeHtml(String(b.id).slice(0, 8).toUpperCase())}</span><span class="badge ${escapeHtml(b.status)}">${escapeHtml(String(b.status).replace('_', ' '))}</span></div>
    <div class="route-line"><i class="route-dot"></i><div><div class="route-label">Pickup</div><div class="route-value">${escapeHtml(b.pickup?.label || 'Pickup')}</div></div></div>
    <div class="route-line destination"><i class="route-dot"></i><div><div class="route-label">Destination</div><div class="route-value">${escapeHtml(b.destination?.label || 'Destination')}</div></div></div>
    <div class="booking-meta"><span>${escapeHtml(b.passengerName || 'Guest')}</span><span>•</span><span>${Number(b.passengers || 1)} pax</span><span>•</span><span>${escapeHtml(b.source || 'dispatch')}</span></div>
    ${driver ? `<div class="booking-driver">${escapeHtml(statusCopy)}</div>` : ''}
  </article>`;
}

function renderBookings(state) {
  const filtered = state.bookings
    .filter((b) => bookingBucket(b.status) === bookingFilter)
    .sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0));
  $('#bookings').innerHTML = filtered.length
    ? filtered.map(bookingCard).join('')
    : `<div class="empty-state"><strong>No ${bookingFilter} bookings</strong><span>${bookingFilter === 'open' ? 'New phone, WhatsApp and web requests will appear here.' : 'Nothing in this queue right now.'}</span></div>`;
}

function renderEvents(state) {
  const events = (state.events || []).slice().reverse().slice(0, 30);
  $('#events').innerHTML = events.map((e) => `<div class="event"><strong>${escapeHtml(String(e.type).replaceAll('_', ' '))}</strong><span class="muted">${new Date(e.at).toLocaleTimeString()}</span> · <span class="mono">${escapeHtml(JSON.stringify(e.payload))}</span></div>`).join('') || '<div class="empty-state"><strong>No recent activity</strong><span>Operational events will appear here.</span></div>';
}

function render(state) {
  lastState = state;
  if (state.tenant?.name) $('#baseName').textContent = state.tenant.name;
  $('#nightMode').textContent = state.nightMode ? 'Night automation ON' : 'Night automation OFF';
  $('#nightMode').className = `btn ${state.nightMode ? 'success' : 'secondary'}`;
  renderStats(state);
  renderDrivers(state);
  renderBookings(state);
  renderEvents(state);
  updateFleetMap(state.drivers);
}

function openBookingDrawer() {
  $('#bookingDrawer').classList.add('open');
  $('#bookingDrawer').setAttribute('aria-hidden', 'false');
  setTimeout(() => $('#passengerName')?.focus(), 50);
}

function closeBookingDrawer() {
  $('#bookingDrawer').classList.remove('open');
  $('#bookingDrawer').setAttribute('aria-hidden', 'true');
}

function createPinMarker(kind, lngLat) {
  const el = document.createElement('div');
  el.style.width = '20px';
  el.style.height = '20px';
  el.style.borderRadius = '50%';
  el.style.border = '3px solid white';
  el.style.boxShadow = '0 3px 10px rgba(0,0,0,.25)';
  el.style.background = kind === 'pickup' ? '#1f9d62' : '#ef6a00';
  return new maplibregl.Marker({ element: el }).setLngLat(lngLat).addTo(map);
}

function beginPin(kind) {
  pinMode = kind;
  closeBookingDrawer();
  setMapMode(`Click map to set ${kind}`, true);
  map.getCanvas().style.cursor = 'crosshair';
}

function finishPin(kind, lngLat) {
  const value = { lat: Number(lngLat.lat.toFixed(6)), lng: Number(lngLat.lng.toFixed(6)) };
  if (kind === 'pickup') {
    pickupPin = value;
    pickupMarker?.remove();
    pickupMarker = createPinMarker('pickup', lngLat);
    $('#pickupPinStatus').textContent = `${value.lat}, ${value.lng}`;
  } else {
    destinationPin = value;
    destinationMarker?.remove();
    destinationMarker = createPinMarker('destination', lngLat);
    $('#destinationPinStatus').textContent = `${value.lat}, ${value.lng}`;
  }
  pinMode = null;
  map.getCanvas().style.cursor = '';
  setMapMode();
  openBookingDrawer();
}

function resetBookingForm() {
  $('#bookingForm').reset();
  $('#passengers').value = '1';
  pickupPin = null;
  destinationPin = null;
  pickupMarker?.remove();
  destinationMarker?.remove();
  pickupMarker = null;
  destinationMarker = null;
  $('#pickupPinStatus').textContent = 'No map pin yet';
  $('#destinationPinStatus').textContent = 'Optional';
  $('#bookingFormStatus').textContent = '';
  $('#bookingFormStatus').className = 'form-status';
}

async function submitBooking(event) {
  event.preventDefault();
  const status = $('#bookingFormStatus');
  const button = $('#createBooking');
  const pickupLabel = $('#pickupLabel').value.trim();
  const destinationLabel = $('#destinationLabel').value.trim();
  if (!pickupPin) {
    status.textContent = 'Pin the pickup on the map before dispatching.';
    status.className = 'form-status error';
    return;
  }
  if (!pickupLabel || !destinationLabel) {
    status.textContent = 'Pickup and destination names are required.';
    status.className = 'form-status error';
    return;
  }

  const payload = {
    passengerName: $('#passengerName').value.trim() || 'Guest',
    passengerPhone: $('#passengerPhone').value.trim(),
    passengers: Number($('#passengers').value || 1),
    notes: $('#bookingNotes').value.trim(),
    pickup: { label: pickupLabel, ...pickupPin },
    destination: { label: destinationLabel, lat: destinationPin?.lat ?? null, lng: destinationPin?.lng ?? null }
  };

  button.disabled = true;
  status.textContent = 'Creating booking and finding a driver…';
  status.className = 'form-status';
  try {
    await api('/api/bookings', { method: 'POST', body: JSON.stringify(payload) });
    status.textContent = 'Booking created.';
    status.className = 'form-status success';
    await refresh();
    setTimeout(() => { closeBookingDrawer(); resetBookingForm(); }, 450);
  } catch (error) {
    status.textContent = error.message || 'Could not create booking.';
    status.className = 'form-status error';
  } finally {
    button.disabled = false;
  }
}

async function refresh() {
  try {
    render(await api('/api/state'));
  } catch (error) {
    if (!String(error.message).includes('Sign in')) console.error(error);
  }
}

map.on('load', () => { if (lastState) updateFleetMap(lastState.drivers); });
map.on('click', (event) => { if (pinMode) finishPin(pinMode, event.lngLat); });

$('#newBookingButton').onclick = openBookingDrawer;
$$('[data-close-booking]').forEach((el) => el.addEventListener('click', closeBookingDrawer));
$('#pinPickup').onclick = () => beginPin('pickup');
$('#pinDestination').onclick = () => beginPin('destination');
$('#bookingForm').addEventListener('submit', submitBooking);

$$('.booking-tab').forEach((button) => button.addEventListener('click', () => {
  bookingFilter = button.dataset.bookingFilter;
  $$('.booking-tab').forEach((b) => b.classList.toggle('active', b === button));
  if (lastState) renderBookings(lastState);
}));

$$('.dispatch-nav-item').forEach((button) => button.addEventListener('click', () => {
  $$('.dispatch-nav-item').forEach((b) => b.classList.toggle('active', b === button));
  const section = button.dataset.section;
  if (section === 'overview') window.scrollTo({ top: 0, behavior: 'smooth' });
  else document.querySelector(`[data-panel="${section}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}));

$('#nightMode').onclick = async () => {
  await api('/api/night-mode', { method: 'POST', body: JSON.stringify({ enabled: !lastState?.nightMode }) });
  await refresh();
};
$('#logout').onclick = () => { clearSession(); location.href = '/login.html'; };

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closeBookingDrawer();
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    openBookingDrawer();
  }
});

await refresh();
setInterval(refresh, 1800);
