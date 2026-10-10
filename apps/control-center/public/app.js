import * as maplibregl from 'https://unpkg.com/maplibre-gl@6.13.0/dist/maplibre-gl.mjs';
import { api, clearSession, membership } from '/session.js';
import { ago, dateTime, escapeHtml } from '/modules/format.js';
import { bookingBucket, renderBookings, renderOverviewBookings, renderTrips } from '/modules/bookings-ui.js';
import { idleFor, renderAnalytics, renderDrivers, renderEvents, renderOverviewDrivers, renderStats } from '/modules/fleet-ui.js';
import { renderCustomers, renderSupport } from '/modules/people-support-ui.js';

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const member = membership();

let state = null;
let currentPage = 'overview';
let bookingFilter = 'open';
let managedBookingId = null;
let pinMode = null;
let pickupPin = null;
let destinationPin = null;
let pickupMarker = null;
let destinationMarker = null;
let fittedFleetOnce = false;
let customerCache = [];
let supportCache = [];
const driverMarkers = new Map();

if (member?.role === 'admin') $('#adminLink').style.display = 'flex';

const pageCopy = {
  overview: 'Fleet status, waiting jobs and active trips.',
  bookings: 'Live, scheduled and completed requests from every channel.',
  drivers: 'Availability, queue fairness and GPS freshness.',
  trips: 'Completed, cancelled and no-show records.',
  customers: 'Repeat customers captured automatically from real bookings.',
  support: 'Operational and app issues reported from the field.',
  analytics: 'Performance calculated from actual booking data.'
};

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

function setMapMode(text = 'Live tracking', pin = false) {
  const el = $('#mapMode');
  if (!el) return;
  el.textContent = text;
  el.classList.toggle('pin-mode', pin);
}

function openPage(name) {
  currentPage = name;
  $$('.dispatch-page').forEach((page) => page.classList.toggle('active', page.dataset.page === name));
  $$('.dispatch-nav-item').forEach((item) => item.classList.toggle('active', item.dataset.pageTarget === name));
  $('#pageSubtitle').textContent = pageCopy[name] || '';
  if (name === 'overview') setTimeout(() => map.resize(), 0);
  if (name === 'customers') loadCustomers();
  if (name === 'support') loadSupport();
}

function updateFleetMap(drivers = []) {
  const liveIds = new Set();
  const bounds = new maplibregl.LngLatBounds();
  let positioned = 0;

  for (const driver of drivers) {
    const lat = Number(driver.location?.lat);
    const lng = Number(driver.location?.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
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
        .setPopup(new maplibregl.Popup({ offset: 18, closeButton: false }))
        .addTo(map);
      record = { marker, el };
      driverMarkers.set(driver.id, record);
    }

    const initials = String(driver.name || 'D').trim().split(/\s+/).map((part) => part[0]).join('').slice(0, 2).toUpperCase();
    record.marker.setLngLat(point);
    record.el.className = `vehicle-marker ${driver.status}`;
    record.el.textContent = initials || 'D';
    record.marker.getPopup().setHTML(`<strong>${escapeHtml(driver.name)}</strong><br>${escapeHtml(driver.vehicle || 'Vehicle not set')}<br><small>${escapeHtml(driver.status)} · GPS ${escapeHtml(ago(driver.location?.capturedAt || driver.lastSeenAt))}</small>`);
  }

  for (const [id, record] of driverMarkers) {
    if (liveIds.has(id)) continue;
    record.marker.remove();
    driverMarkers.delete(id);
  }

  if (!fittedFleetOnce && positioned && map.loaded()) {
    fittedFleetOnce = true;
    if (positioned === 1) map.easeTo({ center: bounds.getCenter(), zoom: 14 });
    else map.fitBounds(bounds, { padding: 64, maxZoom: 14, duration: 0 });
  }
}

function renderState(nextState) {
  state = nextState;
  if (state.tenant?.name) $('#baseName').textContent = state.tenant.name;
  $('#nightMode').textContent = state.nightMode ? 'Night automation on' : 'Night automation off';
  $('#nightMode').classList.toggle('is-on', Boolean(state.nightMode));

  renderStats($('#stats'), state);
  renderOverviewBookings($('#overviewBookings'), state);
  renderOverviewDrivers($('#overviewDrivers'), state);
  renderEvents($('#overviewEvents'), state);
  renderBookings($('#bookings'), state, bookingFilter);
  renderDrivers($('#driversBody'), state);
  renderTrips($('#tripsList'), state);
  renderAnalytics($('#analyticsGrid'), state);
  updateFleetMap(state.drivers);

  if (managedBookingId) populateManageDrawer();
}

async function refresh() {
  try {
    renderState(await api('/api/state'));
  } catch (error) {
    if (!String(error.message).includes('Sign in')) console.error('State refresh failed', error);
  }
}

async function loadCustomers() {
  try {
    const result = await api('/api/customers?limit=150');
    customerCache = result.customers || [];
    renderCustomers($('#customersList'), customerCache);
  } catch (error) {
    $('#customersList').innerHTML = `<div class="empty-state"><strong>Could not load customers</strong><span>${escapeHtml(error.message)}</span></div>`;
  }
}

async function loadSupport() {
  try {
    const result = await api('/api/support?limit=150');
    supportCache = result.tickets || [];
    renderSupport($('#supportList'), supportCache);
  } catch (error) {
    $('#supportList').innerHTML = `<div class="empty-state"><strong>Could not load support</strong><span>${escapeHtml(error.message)}</span></div>`;
  }
}

function openBookingDrawer() {
  $('#bookingDrawer').classList.add('open');
  $('#bookingDrawer').setAttribute('aria-hidden', 'false');
  setTimeout(() => $('#passengerName')?.focus(), 30);
}

function closeBookingDrawer() {
  $('#bookingDrawer').classList.remove('open');
  $('#bookingDrawer').setAttribute('aria-hidden', 'true');
}

function openManageDrawer(bookingId) {
  managedBookingId = bookingId;
  populateManageDrawer();
  $('#manageDrawer').classList.add('open');
  $('#manageDrawer').setAttribute('aria-hidden', 'false');
}

function closeManageDrawer() {
  managedBookingId = null;
  $('#manageDrawer').classList.remove('open');
  $('#manageDrawer').setAttribute('aria-hidden', 'true');
  $('#manageStatus').textContent = '';
  $('#cancelReason').value = '';
}

function managedBooking() {
  return state?.bookings?.find((booking) => booking.id === managedBookingId) || null;
}

function populateManageDrawer() {
  const booking = managedBooking();
  if (!booking) return;
  const assigned = state.drivers.find((driver) => driver.id === booking.assignedDriverId);
  $('#manageTitle').textContent = `#${String(booking.id).slice(0, 8).toUpperCase()}`;
  const summary = $('#manageSummary');
  const reservationPanel = summary.querySelector('.reservation-panel');
  summary.innerHTML = `<div class="manage-route"><span>Pickup</span><strong>${escapeHtml(booking.pickup?.label || 'Pickup')}</strong></div>
    <div class="manage-route destination"><span>Destination</span><strong>${escapeHtml(booking.destination?.label || 'Destination')}</strong></div>
    <div class="manage-meta"><span>${escapeHtml(booking.passengerName || 'Guest')}</span><span>${escapeHtml(booking.status)}</span>${booking.scheduledFor ? `<span>${escapeHtml(dateTime(booking.scheduledFor))}</span>` : ''}${assigned ? `<span>${escapeHtml(assigned.name)}</span>` : ''}</div>`;
  if (reservationPanel && booking.status === 'scheduled') summary.appendChild(reservationPanel);

  const eligible = state.drivers.filter((driver) => driver.status === 'available' || driver.id === booking.assignedDriverId);
  $('#manageDriver').innerHTML = `<option value="">Choose available driver</option>${eligible.map((driver) => `<option value="${escapeHtml(driver.id)}" ${driver.id === booking.assignedDriverId ? 'selected' : ''}>${escapeHtml(driver.name)} · ${escapeHtml(driver.vehicle || 'No vehicle')} ${idleFor(driver) ? `· ${escapeHtml(idleFor(driver))}` : ''}</option>`).join('')}`;

  const terminal = ['completed', 'cancelled', 'no_show'].includes(booking.status);
  $('#manageAssign').hidden = terminal || booking.status === 'in_progress' || booking.status === 'scheduled';
  $('#requeueButton').hidden = terminal || booking.status === 'in_progress' || booking.status === 'scheduled';
  $('#noShowButton').hidden = booking.status !== 'arrived';
  $('#cancelBookingButton').hidden = booking.status === 'completed' || booking.status === 'cancelled' || booking.status === 'no_show';
}

function createPinMarker(kind, lngLat) {
  const el = document.createElement('div');
  el.className = `pin-marker ${kind}`;
  return new maplibregl.Marker({ element: el }).setLngLat(lngLat).addTo(map);
}

function beginPin(kind) {
  pinMode = kind;
  closeBookingDrawer();
  openPage('overview');
  setMapMode(`Set ${kind} on map`, true);
  map.getCanvas().style.cursor = 'crosshair';
}

function finishPin(kind, lngLat) {
  const value = { lat: Number(lngLat.lat.toFixed(6)), lng: Number(lngLat.lng.toFixed(6)) };
  if (kind === 'pickup') {
    pickupPin = value;
    pickupMarker?.remove();
    pickupMarker = createPinMarker('pickup', lngLat);
    $('#pickupPinStatus').textContent = 'Pickup set';
  } else {
    destinationPin = value;
    destinationMarker?.remove();
    destinationMarker = createPinMarker('destination', lngLat);
    $('#destinationPinStatus').textContent = 'Destination set';
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
  $('#pickupPinStatus').textContent = 'Pin required';
  $('#destinationPinStatus').textContent = 'Pin optional';
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
    status.textContent = 'Set the pickup on the map.';
    status.className = 'form-status error';
    return;
  }
  if (!pickupLabel || !destinationLabel) {
    status.textContent = 'Pickup and destination are required.';
    status.className = 'form-status error';
    return;
  }

  const scheduledValue = $('#scheduledFor').value;
  const payload = {
    passengerName: $('#passengerName').value.trim() || 'Guest',
    passengerPhone: $('#passengerPhone').value.trim(),
    passengers: Number($('#passengers').value || 1),
    notes: $('#bookingNotes').value.trim(),
    scheduledFor: scheduledValue ? new Date(scheduledValue).toISOString() : null,
    pickup: { label: pickupLabel, ...pickupPin },
    destination: { label: destinationLabel, lat: destinationPin?.lat ?? null, lng: destinationPin?.lng ?? null }
  };

  button.disabled = true;
  status.textContent = payload.scheduledFor ? 'Scheduling booking…' : 'Creating booking…';
  status.className = 'form-status';
  try {
    const created = await api('/api/bookings', { method:'POST', body:JSON.stringify(payload) });
    status.textContent = created.status === 'scheduled' ? 'Booking scheduled.' : 'Booking created and dispatch started.';
    status.className = 'form-status success';
    await refresh();
    await loadCustomers();
    setTimeout(() => { closeBookingDrawer(); resetBookingForm(); }, 450);
  } catch (error) {
    status.textContent = error.message || 'Could not create booking.';
    status.className = 'form-status error';
  } finally {
    button.disabled = false;
  }
}

async function assignManagedBooking() {
  const booking = managedBooking();
  const driverId = $('#manageDriver').value;
  if (!booking || !driverId) return;
  const status = $('#manageStatus');
  status.textContent = 'Assigning driver…';
  try {
    await api(`/api/bookings/${encodeURIComponent(booking.id)}/assign`, { method:'POST', body:JSON.stringify({ driverId }) });
    status.textContent = 'Driver assigned.';
    status.className = 'form-status success';
    await refresh();
  } catch (error) {
    status.textContent = error.message;
    status.className = 'form-status error';
  }
}

async function requeueManagedBooking() {
  const booking = managedBooking();
  if (!booking) return;
  const status = $('#manageStatus');
  status.textContent = 'Finding another driver…';
  try {
    await api(`/api/bookings/${encodeURIComponent(booking.id)}/requeue`, { method:'POST', body:'{}' });
    status.textContent = 'Booking returned to dispatch queue.';
    status.className = 'form-status success';
    await refresh();
  } catch (error) {
    status.textContent = error.message;
    status.className = 'form-status error';
  }
}

async function cancelManagedBooking(noShow = false) {
  const booking = managedBooking();
  if (!booking) return;
  const reason = $('#cancelReason').value.trim();
  const status = $('#manageStatus');
  if (!reason) {
    status.textContent = 'Enter a reason first.';
    status.className = 'form-status error';
    return;
  }
  status.textContent = noShow ? 'Recording no-show…' : 'Cancelling booking…';
  try {
    const action = noShow ? 'no-show' : 'cancel';
    await api(`/api/bookings/${encodeURIComponent(booking.id)}/${action}`, { method:'POST', body:JSON.stringify({ reason, code:noShow ? 'passenger_no_show' : 'dispatcher_cancelled' }) });
    status.textContent = noShow ? 'No-show recorded.' : 'Booking cancelled.';
    status.className = 'form-status success';
    await refresh();
    setTimeout(closeManageDrawer, 450);
  } catch (error) {
    status.textContent = error.message;
    status.className = 'form-status error';
  }
}

async function resolveTicket(ticketId) {
  const button = document.querySelector(`[data-resolve-ticket="${CSS.escape(ticketId)}"]`);
  if (button) { button.disabled = true; button.textContent = 'Resolving…'; }
  try {
    await api(`/api/support/${encodeURIComponent(ticketId)}/resolve`, { method:'POST', body:JSON.stringify({ resolutionNote:'' }) });
    await loadSupport();
  } catch (error) {
    console.error('Could not resolve support ticket', error);
    if (button) { button.disabled = false; button.textContent = 'Resolve'; }
  }
}

$$('.dispatch-nav-item').forEach((button) => button.addEventListener('click', () => openPage(button.dataset.pageTarget)));
$$('[data-open-page]').forEach((button) => button.addEventListener('click', () => openPage(button.dataset.openPage)));
$$('[data-close-booking]').forEach((el) => el.addEventListener('click', closeBookingDrawer));
$$('[data-close-manage]').forEach((el) => el.addEventListener('click', closeManageDrawer));

$('#newBookingButton').addEventListener('click', openBookingDrawer);
$('#pinPickup').addEventListener('click', () => beginPin('pickup'));
$('#pinDestination').addEventListener('click', () => beginPin('destination'));
$('#bookingForm').addEventListener('submit', submitBooking);
$('#assignDriverButton').addEventListener('click', assignManagedBooking);
$('#requeueButton').addEventListener('click', requeueManagedBooking);
$('#cancelBookingButton').addEventListener('click', () => cancelManagedBooking(false));
$('#noShowButton').addEventListener('click', () => cancelManagedBooking(true));

$$('.booking-tab').forEach((button) => button.addEventListener('click', () => {
  bookingFilter = button.dataset.bookingFilter;
  $$('.booking-tab').forEach((item) => item.classList.toggle('active', item === button));
  if (state) renderBookings($('#bookings'), state, bookingFilter);
}));

document.addEventListener('click', (event) => {
  const manage = event.target.closest('[data-manage-booking]');
  if (manage) openManageDrawer(manage.dataset.manageBooking);
  const resolve = event.target.closest('[data-resolve-ticket]');
  if (resolve) resolveTicket(resolve.dataset.resolveTicket);
});

$('#nightMode').addEventListener('click', async () => {
  if (!state) return;
  try {
    await api('/api/night-mode', { method:'POST', body:JSON.stringify({ enabled: !state.nightMode }) });
    await refresh();
  } catch (error) { console.error(error); }
});

$('#logout').addEventListener('click', () => {
  clearSession();
  location.href = '/login.html';
});

map.on('load', () => { if (state) updateFleetMap(state.drivers); });
map.on('click', (event) => { if (pinMode) finishPin(pinMode, event.lngLat); });

openPage('overview');
await refresh();
setInterval(refresh, 5000);
setInterval(() => { if (currentPage === 'support') loadSupport(); }, 15000);
