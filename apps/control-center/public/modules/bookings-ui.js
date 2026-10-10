import { ago, dateTime, escapeHtml, statusLabel } from './format.js';

export function bookingBucket(status) {
  if (status === 'scheduled') return 'scheduled';
  if (['pending', 'offering', 'unfulfilled'].includes(status)) return 'open';
  if (['assigned', 'arrived', 'in_progress'].includes(status)) return 'active';
  return 'done';
}

function driverName(state, id) {
  return state?.drivers?.find((driver) => driver.id === id)?.name || 'Driver';
}

function etaCopy(booking) {
  const seconds = Number(booking.offerEtaSeconds);
  if (!Number.isFinite(seconds) || seconds < 0) return '';
  const minutes = Math.max(1, Math.round(seconds / 60));
  const distance = Number(booking.offerRoadDistanceKm);
  const road = Number.isFinite(distance) && distance >= 0 ? ` · ${distance.toFixed(1)} km road` : '';
  return ` · ~${minutes} min to pickup${road}`;
}

function bookingStatusCopy(state, booking) {
  const driver = booking.assignedDriverId ? driverName(state, booking.assignedDriverId) : booking.currentOfferDriverId ? driverName(state, booking.currentOfferDriverId) : '';
  if (booking.status === 'scheduled') {
    const when = booking.scheduledFor ? dateTime(booking.scheduledFor) : 'Scheduled';
    return booking.reservedDriverId ? `${driverName(state, booking.reservedDriverId)} reserved · ${when}` : `Unassigned · ${when}`;
  }
  if (booking.status === 'offering') return `Offer sent to ${driver}${etaCopy(booking)}`;
  if (booking.status === 'assigned') return `${driver} heading to pickup`;
  if (booking.status === 'arrived') return `${driver} waiting${booking.arrivedAt ? ` · ${ago(booking.arrivedAt)}` : ''}`;
  if (booking.status === 'in_progress') return `${driver} on trip`;
  if (booking.status === 'unfulfilled') return 'No available driver accepted this request';
  if (booking.status === 'cancelled') return booking.cancellationReason || 'Cancelled';
  if (booking.status === 'no_show') return booking.cancellationReason || 'Passenger no-show';
  return statusLabel(booking.status);
}

export function bookingCard(state, booking, { compact = false } = {}) {
  const scheduled = booking.scheduledFor ? `<span>${escapeHtml(dateTime(booking.scheduledFor))}</span>` : '';
  const manage = ['completed'].includes(booking.status) ? '' : `<button class="booking-manage text-action" data-manage-booking="${escapeHtml(booking.id)}">Manage</button>`;
  return `<article class="dispatch-booking ${compact ? 'compact-booking' : ''}" data-booking-id="${escapeHtml(booking.id)}">
    <div class="dispatch-booking-head">
      <div><span class="booking-id">#${escapeHtml(String(booking.id).slice(0, 8).toUpperCase())}</span>${scheduled}</div>
      <div class="booking-head-actions"><span class="badge ${escapeHtml(booking.status)}">${escapeHtml(statusLabel(booking.status))}</span>${manage}</div>
    </div>
    <div class="route-line"><i class="route-dot"></i><div><div class="route-label">Pickup</div><div class="route-value">${escapeHtml(booking.pickup?.label || 'Pickup')}</div></div></div>
    <div class="route-line destination"><i class="route-dot"></i><div><div class="route-label">Destination</div><div class="route-value">${escapeHtml(booking.destination?.label || 'Destination')}</div></div></div>
    <div class="booking-meta"><span>${escapeHtml(booking.passengerName || 'Guest')}</span><span>·</span><span>${Number(booking.passengers || 1)} pax</span><span>·</span><span>${escapeHtml(booking.source || 'dispatch')}</span></div>
    <div class="booking-driver">${escapeHtml(bookingStatusCopy(state, booking))}</div>
  </article>`;
}

export function renderBookings(container, state, filter = 'open') {
  if (!container) return;
  const rows = [...(state.bookings || [])]
    .filter((booking) => bookingBucket(booking.status) === filter)
    .sort((a, b) => {
      if (filter === 'scheduled') return new Date(a.scheduledFor || 0) - new Date(b.scheduledFor || 0);
      return new Date(b.createdAt || 0) - new Date(a.createdAt || 0);
    });
  container.innerHTML = rows.length
    ? rows.map((booking) => bookingCard(state, booking)).join('')
    : `<div class="empty-state"><strong>No ${escapeHtml(filter)} bookings</strong><span>Nothing needs attention in this queue.</span></div>`;
}

export function renderOverviewBookings(container, state) {
  if (!container) return;
  const priority = [...(state.bookings || [])]
    .filter((booking) => ['pending', 'offering', 'unfulfilled', 'arrived', 'assigned'].includes(booking.status))
    .sort((a, b) => {
      const order = { unfulfilled:0, pending:1, offering:2, arrived:3, assigned:4 };
      return (order[a.status] ?? 9) - (order[b.status] ?? 9) || new Date(a.createdAt || 0) - new Date(b.createdAt || 0);
    })
    .slice(0, 5);
  container.innerHTML = priority.length
    ? priority.map((booking) => bookingCard(state, booking, { compact:true })).join('')
    : '<div class="empty-state"><strong>Queue clear</strong><span>No booking currently needs dispatcher attention.</span></div>';
}

export function renderTrips(container, state) {
  if (!container) return;
  const rows = [...(state.bookings || [])]
    .filter((booking) => ['completed', 'cancelled', 'no_show'].includes(booking.status))
    .sort((a, b) => new Date(b.completedAt || b.cancelledAt || b.createdAt || 0) - new Date(a.completedAt || a.cancelledAt || a.createdAt || 0));
  container.innerHTML = rows.length ? rows.map((booking) => `<article class="trip-row">
    <div class="trip-main"><strong>${escapeHtml(booking.pickup?.label || 'Pickup')} → ${escapeHtml(booking.destination?.label || 'Destination')}</strong><span>${escapeHtml(booking.passengerName || 'Guest')} · ${escapeHtml(dateTime(booking.completedAt || booking.cancelledAt || booking.createdAt))}</span></div>
    <div class="trip-side"><span class="badge ${escapeHtml(booking.status)}">${escapeHtml(statusLabel(booking.status))}</span>${booking.assignedDriverId ? `<span>${escapeHtml(driverName(state, booking.assignedDriverId))}</span>` : ''}</div>
  </article>`).join('') : '<div class="empty-state"><strong>No trip history yet</strong><span>Completed and cancelled trips will appear here.</span></div>';
}
