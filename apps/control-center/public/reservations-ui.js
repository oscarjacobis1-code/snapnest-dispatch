import { api } from '/session.js';
import { escapeHtml } from '/modules/format.js';

let managedBookingId = null;

const style = document.createElement('style');
style.textContent = `
  .reservation-panel{margin-top:14px;padding:14px;border-radius:14px;background:rgba(13,42,64,.07);border:1px solid rgba(13,42,64,.12)}
  .reservation-panel .reservation-label{display:block;margin-bottom:8px;font-size:10px;font-weight:800;letter-spacing:.08em;color:#68747c;text-transform:uppercase}
  .reservation-row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;align-items:center}
  .reservation-row select{min-width:0;width:100%;height:42px;border-radius:10px;border:1px solid #dfe4e7;background:#fff;padding:0 10px;color:#17232c}
  .reservation-current{margin-top:8px;font-size:10px;color:#68747c}
  .reservation-clear{margin-top:7px;border:0;background:transparent;color:#b54a51;font-size:10px;font-weight:700;cursor:pointer;padding:0}
  .reservation-note{margin-top:9px;font-size:10px;line-height:1.45;color:#68747c}
`;
document.head.appendChild(style);

document.addEventListener('click', (event) => {
  const manage = event.target.closest('[data-manage-booking]');
  if (!manage) return;
  managedBookingId = manage.dataset.manageBooking;
  setTimeout(renderReservationPanel, 60);
});

async function renderReservationPanel() {
  if (!managedBookingId) return;
  const summary = document.querySelector('#manageSummary');
  if (!summary) return;
  summary.querySelector('.reservation-panel')?.remove();

  try {
    const state = await api('/api/state');
    const booking = (state.bookings || []).find((item) => item.id === managedBookingId);
    if (!booking || booking.status !== 'scheduled') return;

    const drivers = state.drivers || [];
    const reserved = drivers.find((driver) => driver.id === booking.reservedDriverId);
    const panel = document.createElement('div');
    panel.className = 'reservation-panel';
    panel.innerHTML = `
      <span class="reservation-label">Driver reservation</span>
      <div class="reservation-row">
        <select id="reservationDriver" aria-label="Reserve scheduled job to driver">
          <option value="">Choose driver</option>
          ${drivers.map((driver) => `<option value="${escapeHtml(driver.id)}" ${driver.id === booking.reservedDriverId ? 'selected' : ''}>${escapeHtml(driver.name)} · ${escapeHtml(driver.vehicle || 'No vehicle')} · ${escapeHtml(driver.status)}</option>`).join('')}
        </select>
        <button id="reserveDriverButton" class="btn primary compact" type="button">${booking.reservedDriverId ? 'Change' : 'Reserve'}</button>
      </div>
      <div class="reservation-current">${reserved ? `Reserved for ${escapeHtml(reserved.name)}. This does not make the driver busy now.` : 'No driver reserved yet.'}</div>
      ${booking.reservedDriverId ? '<button id="clearReservationButton" class="reservation-clear" type="button">Clear reservation</button>' : ''}
      <div class="reservation-note">15 minutes before pickup, SnapNest will try the reserved driver first. If they are unavailable, normal dispatch takes over.</div>
      <div id="reservationStatus" class="form-status"></div>`;
    summary.appendChild(panel);

    panel.querySelector('#reserveDriverButton')?.addEventListener('click', async () => {
      const driverId = panel.querySelector('#reservationDriver')?.value;
      const status = panel.querySelector('#reservationStatus');
      if (!driverId) {
        status.textContent = 'Choose a driver first.';
        status.className = 'form-status error';
        return;
      }
      status.textContent = 'Saving reservation…';
      status.className = 'form-status';
      try {
        await api(`/api/bookings/${encodeURIComponent(managedBookingId)}/reserve`, { method: 'POST', body: JSON.stringify({ driverId }) });
        status.textContent = 'Driver reserved.';
        status.className = 'form-status success';
        document.querySelector('.dispatch-nav-item[data-page-target="bookings"]')?.click();
        setTimeout(renderReservationPanel, 180);
      } catch (error) {
        status.textContent = error.message || 'Could not reserve driver.';
        status.className = 'form-status error';
      }
    });

    panel.querySelector('#clearReservationButton')?.addEventListener('click', async () => {
      const status = panel.querySelector('#reservationStatus');
      status.textContent = 'Clearing reservation…';
      try {
        await api(`/api/bookings/${encodeURIComponent(managedBookingId)}/clear-reservation`, { method: 'POST', body: '{}' });
        status.textContent = 'Reservation cleared.';
        status.className = 'form-status success';
        setTimeout(renderReservationPanel, 180);
      } catch (error) {
        status.textContent = error.message || 'Could not clear reservation.';
        status.className = 'form-status error';
      }
    });
  } catch (error) {
    console.warn('Reservation controls unavailable', error);
  }
}
