import { api, clearSession } from '/session.js';
import { escapeHtml } from '/modules/format.js';

function mountShiftUi() {
  if (!document.querySelector('#shiftButton')) {
    const button = document.createElement('button');
    button.id = 'shiftButton';
    button.className = 'btn secondary';
    button.type = 'button';
    button.textContent = 'Shift';
    const newBooking = document.querySelector('#newBookingButton');
    if (newBooking) newBooking.insertAdjacentElement('beforebegin', button);
    else document.querySelector('.dispatch-actions')?.appendChild(button);
  }

  if (!document.querySelector('#shiftDrawer')) {
    const wrapper = document.createElement('div');
    wrapper.id = 'shiftDrawer';
    wrapper.className = 'booking-drawer';
    wrapper.setAttribute('aria-hidden', 'true');
    wrapper.innerHTML = `
      <div class="booking-drawer-backdrop" data-close-shift></div>
      <section class="booking-drawer-panel shift-drawer-panel" role="dialog" aria-modal="true" aria-labelledby="shiftDrawerTitle">
        <div class="drawer-heading"><div><span class="dispatch-kicker">SHIFT HANDOVER</span><h2 id="shiftDrawerTitle">Dispatcher shift</h2></div><button class="icon-button" type="button" data-close-shift aria-label="Close">×</button></div>
        <p id="handoverPrompt" class="shift-prompt">Review recent handovers. Add a note only when ending your shift.</p>
        <div id="shiftCurrent" class="shift-current"></div>
        <div class="manage-section">
          <label for="handoverNote">Handover note</label>
          <textarea id="handoverNote" maxlength="2000" placeholder="Anything the next dispatcher needs to know: waiting passengers, driver issues, scheduled pickups, safety or payment concerns."></textarea>
        </div>
        <div id="handoverStatus" class="form-status"></div>
        <button id="endShiftButton" class="btn danger full" type="button" hidden>End shift & sign out</button>
        <div class="shift-history-heading"><span class="dispatch-kicker">RECENT HANDOVERS</span><strong>What the last dispatcher left</strong></div>
        <div id="shiftHandovers" class="shift-handovers"></div>
      </section>`;
    document.body.appendChild(wrapper);
  }

  if (!document.querySelector('#operatorShiftStyles')) {
    const style = document.createElement('style');
    style.id = 'operatorShiftStyles';
    style.textContent = `
      .shift-prompt{margin:-6px 0 16px;color:#748188;font-size:11px;line-height:1.5}
      .shift-current{margin-bottom:18px;padding:14px;border:1px solid #e0e5e7;border-radius:10px;background:#f8fafb}
      .shift-current-row{display:flex;align-items:center;justify-content:space-between;gap:14px}.shift-current-row>div:first-child{display:grid;gap:3px}.shift-current-row strong{font-size:13px}.shift-current-row small{font-size:9px;color:#7e8a91}.shift-duration{font-size:20px;font-weight:750;color:#173f5f;letter-spacing:-.03em}
      .shift-history-heading{display:grid;gap:4px;margin:22px 0 10px}.shift-history-heading strong{font-size:13px}.shift-handovers{display:grid;gap:8px}.handover-card{border:1px solid #e1e6e8;border-radius:10px;padding:12px;background:#fff}.handover-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.handover-head>div{display:grid;gap:2px;min-width:0}.handover-head strong{font-size:11px;overflow:hidden;text-overflow:ellipsis}.handover-head span{font-size:9px;color:#849097}.handover-card p{margin:9px 0;font-size:11px;line-height:1.5;color:#34434c;white-space:pre-wrap}.handover-chips{display:flex;gap:5px;flex-wrap:wrap}.handover-chip{border-radius:5px;background:#eef2f4;color:#68747c;padding:4px 6px;font-size:8px}.handover-chip strong{color:#173f5f}.handover-chip.danger{background:#fff0f0;color:#a93838}.handover-chip.danger strong{color:#a93838}
      .support-description{margin:7px 0 2px!important;font-size:11px!important;line-height:1.45;color:#34434c!important;white-space:pre-wrap}.support-reference{font-size:8px;color:#8b969c}.support-actions{display:flex;flex-direction:column;align-items:flex-end;gap:4px;min-width:92px}
      @media(max-width:760px){.shift-drawer-panel{width:min(440px,100vw)}.handover-head{align-items:flex-start}.shift-current-row{align-items:flex-start}.support-actions{min-width:72px}}
    `;
    document.head.appendChild(style);
  }
}

mountShiftUi();

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
