import { api, clearSession } from '/session.js';
import { escapeHtml } from '/modules/format.js';

const $ = (selector) => document.querySelector(selector);
const shiftButton = $('#shiftButton');
const drawer = $('#shiftDrawer');
const currentEl = $('#shiftCurrent');
const handoversEl = $('#shiftHandovers');
const noteEl = $('#handoverNote');
const statusEl = $('#handoverStatus');
const endButton = $('#endShiftButton');
const logout = $('#logout');

let current = null;
let handovers = [];
let logoutPending = false;
let timer = null;

function formatWhen(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat(undefined, {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'
  }).format(date);
}

function formatDuration(startedAt, endedAt = null) {
  const start = new Date(startedAt || 0).getTime();
  const end = endedAt ? new Date(endedAt).getTime() : Date.now();
  if (!Number.isFinite(start) || !Number.isFinite(end) || !start) return '—';
  const minutes = Math.max(0, Math.floor((end - start) / 60000));
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return hours ? `${hours}h ${mins}m` : `${mins}m`;
}

function snapshotChips(snapshot = {}) {
  const entries = [
    ['Waiting', snapshot.waitingBookings],
    ['Active', snapshot.activeTrips],
    ['Scheduled', snapshot.scheduledBookings],
    ['Support', snapshot.openSupport],
    ['Safety', snapshot.safetyAlerts]
  ].filter(([, value]) => Number.isFinite(Number(value)));
  return entries.map(([label, value]) => `<span class="handover-chip ${label === 'Safety' && Number(value) > 0 ? 'danger' : ''}">${escapeHtml(label)} <strong>${Number(value)}</strong></span>`).join('');
}

function render() {
  if (currentEl) {
    if (current) {
      currentEl.innerHTML = `<div class="shift-current-row"><div><span class="dispatch-kicker">ON SHIFT</span><strong>${escapeHtml(current.operator_email || 'Dispatcher')}</strong><small>Started ${escapeHtml(formatWhen(current.started_at))}</small></div><div class="shift-duration" id="shiftDuration">${escapeHtml(formatDuration(current.started_at))}</div></div>`;
    } else {
      currentEl.innerHTML = '<div class="empty-state"><strong>No active operator shift</strong><span>Starting the Control Center will open one automatically.</span></div>';
    }
  }

  if (handoversEl) {
    handoversEl.innerHTML = handovers.length ? handovers.map((handover) => `<article class="handover-card">
      <div class="handover-head"><div><strong>${escapeHtml(handover.operator_email || handover.role || 'Dispatcher')}</strong><span>${escapeHtml(formatWhen(handover.ended_at))} · ${escapeHtml(formatDuration(handover.started_at, handover.ended_at))}</span></div><span class="badge completed">${escapeHtml(handover.role || 'dispatcher')}</span></div>
      <p>${escapeHtml(handover.handover_note || '')}</p>
      <div class="handover-chips">${snapshotChips(handover.handover_snapshot || {})}</div>
    </article>`).join('') : '<div class="empty-state"><strong>No handovers yet</strong><span>The previous dispatcher’s handover will appear here.</span></div>';
  }
}

function openDrawer({ ending = false } = {}) {
  if (!drawer) return;
  logoutPending = ending;
  drawer.classList.add('open');
  drawer.setAttribute('aria-hidden', 'false');
  $('#shiftDrawerTitle').textContent = ending ? 'End shift & hand over' : 'Dispatcher shift';
  $('#handoverPrompt').textContent = ending
    ? 'Leave the next dispatcher the information they need before you sign out.'
    : 'Review recent handovers. Add a note only when ending your shift.';
  endButton.hidden = !ending;
  noteEl.closest('.manage-section').hidden = !ending;
  statusEl.textContent = '';
  setTimeout(() => ending ? noteEl?.focus() : null, 30);
}

function closeDrawer() {
  logoutPending = false;
  drawer?.classList.remove('open');
  drawer?.setAttribute('aria-hidden', 'true');
  statusEl.textContent = '';
}

async function loadShift() {
  try {
    const data = await api('/api/operator-shift?limit=8');
    current = data.current || null;
    handovers = data.handovers || [];
    render();
  } catch (error) {
    console.error('Could not load operator shift', error);
  }
}

async function touchShift() {
  try {
    await api('/api/operator-shift/start', { method: 'POST', body: '{}' });
    await loadShift();
  } catch (error) {
    console.error('Could not start operator shift', error);
    if (statusEl) {
      statusEl.textContent = error.message || 'Could not start shift tracking.';
      statusEl.className = 'form-status error';
    }
  }
}

async function endShiftAndSignOut() {
  const note = noteEl?.value.trim() || '';
  if (!note) {
    statusEl.textContent = 'Add a handover note before ending your shift.';
    statusEl.className = 'form-status error';
    noteEl?.focus();
    return;
  }
  endButton.disabled = true;
  statusEl.textContent = 'Saving handover…';
  statusEl.className = 'form-status';
  try {
    await api('/api/operator-shift/end', { method: 'POST', body: JSON.stringify({ note }) });
    clearInterval(timer);
    clearSession();
    location.href = '/login.html';
  } catch (error) {
    statusEl.textContent = error.message || 'Could not save handover.';
    statusEl.className = 'form-status error';
    endButton.disabled = false;
  }
}

shiftButton?.addEventListener('click', async () => {
  await loadShift();
  openDrawer();
});

drawer?.querySelectorAll('[data-close-shift]').forEach((element) => element.addEventListener('click', closeDrawer));
endButton?.addEventListener('click', endShiftAndSignOut);

// Capture phase intentionally intercepts app.js's legacy direct sign-out handler.
logout?.addEventListener('click', async (event) => {
  event.preventDefault();
  event.stopImmediatePropagation();
  await loadShift();
  openDrawer({ ending: true });
}, true);

await touchShift();
timer = setInterval(touchShift, 60_000);
setInterval(() => {
  const duration = $('#shiftDuration');
  if (duration && current) duration.textContent = formatDuration(current.started_at);
}, 30_000);
