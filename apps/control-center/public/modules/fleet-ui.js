import { ago, durationMinutes, escapeHtml } from './format.js';

export function idleFor(driver) {
  if (!driver.availableSince || driver.status !== 'available') return '';
  const minutes = Math.max(0, Math.round((Date.now() - Number(driver.availableSince)) / 60000));
  return minutes < 1 ? 'just available' : `${minutes}m idle`;
}

export function renderStats(container, state) {
  if (!container) return;
  const available = state.drivers.filter((d) => d.status === 'available').length;
  const busy = state.drivers.filter((d) => d.status === 'busy').length;
  const waiting = state.bookings.filter((b) => ['pending','offering','unfulfilled'].includes(b.status)).length;
  const active = state.bookings.filter((b) => ['assigned','arrived','in_progress'].includes(b.status)).length;
  container.innerHTML = [
    ['Available', available, 'available', `${state.drivers.length} drivers`],
    ['Busy', busy, 'busy', 'Assigned or on trip'],
    ['Waiting', waiting, 'open', 'Needs attention'],
    ['Active', active, 'active', 'Pickup + trips']
  ].map(([label,value,kind,note]) => `<div class="dispatch-stat"><div class="dispatch-stat-top"><span>${label}</span><i class="dispatch-stat-dot ${kind}"></i></div><strong>${value}</strong><small>${note}</small></div>`).join('');
}

export function renderDrivers(tableBody, state) {
  if (!tableBody) return;
  const order = { available:0, offered:1, busy:2, unavailable:3, offline:4 };
  const drivers = [...state.drivers].sort((a,b) => (order[a.status] ?? 9) - (order[b.status] ?? 9) || Number(a.queueRank ?? 999) - Number(b.queueRank ?? 999));
  tableBody.innerHTML = drivers.length ? drivers.map((d) => `<tr>
    <td><strong>${escapeHtml(d.name)}</strong><div class="muted">${escapeHtml(d.vehicle || 'Vehicle not set')}${idleFor(d) ? ` · ${escapeHtml(idleFor(d))}` : ''}</div></td>
    <td><span class="badge ${escapeHtml(d.status)}">${escapeHtml(d.status)}</span></td>
    <td>${d.queueRank ?? '—'}</td>
    <td>${d.recentDeclines ?? 0}</td>
    <td>${escapeHtml(ago(d.location?.capturedAt || d.lastSeenAt))}</td>
  </tr>`).join('') : '<tr><td colspan="5" class="muted">No drivers found.</td></tr>';
}

export function renderOverviewDrivers(container, state) {
  if (!container) return;
  const drivers = [...state.drivers].filter((d) => d.status === 'available').sort((a,b) => Number(a.queueRank ?? 999)-Number(b.queueRank ?? 999)).slice(0,6);
  container.innerHTML = drivers.length ? drivers.map((d) => `<div class="compact-driver"><div><strong>${escapeHtml(d.name)}</strong><span>${escapeHtml(d.vehicle || 'Vehicle not set')}</span></div><div><span class="status-dot available"></span>${escapeHtml(idleFor(d) || 'available')}</div></div>`).join('') : '<div class="empty-state"><strong>No available drivers</strong><span>Check unavailable or busy drivers before accepting more demand.</span></div>';
}

export function renderEvents(container, state) {
  if (!container) return;
  const events = (state.events || []).slice(0,10);
  container.innerHTML = events.length ? events.map((event) => `<div class="event"><strong>${escapeHtml(String(event.type).replaceAll('_',' ').replaceAll('.',' · '))}</strong><span>${escapeHtml(ago(event.at))}</span></div>`).join('') : '<div class="empty-state"><strong>No recent activity</strong><span>Operational changes will appear here.</span></div>';
}

export function renderAnalytics(container, state) {
  if (!container) return;
  const bookings = state.bookings || [];
  const completed = bookings.filter((b) => b.status === 'completed');
  const cancelled = bookings.filter((b) => ['cancelled','no_show'].includes(b.status));
  const created = bookings.length;
  const completionRate = created ? Math.round((completed.length / created) * 100) : 0;
  const waits = bookings.map((b) => durationMinutes(b.arrivedAt, b.startedAt)).filter((v) => v !== null);
  const pickupTimes = bookings.map((b) => durationMinutes(b.assignedAt, b.arrivedAt)).filter((v) => v !== null);
  const avg = (values) => values.length ? Math.round(values.reduce((a,b)=>a+b,0)/values.length) : null;
  const scheduled = bookings.filter((b) => b.status === 'scheduled').length;
  const unfulfilled = bookings.filter((b) => b.status === 'unfulfilled').length;
  const metrics = [
    ['Bookings observed', created, 'Current loaded operating window'],
    ['Completion rate', `${completionRate}%`, `${completed.length} completed`],
    ['Avg pickup', avg(pickupTimes) == null ? '—' : `${avg(pickupTimes)} min`, 'Assigned → arrived'],
    ['Avg wait', avg(waits) == null ? '—' : `${avg(waits)} min`, 'Arrived → trip start'],
    ['Cancelled / no-show', cancelled.length, 'Customer + operational loss'],
    ['Unfulfilled', unfulfilled, 'Demand not served'],
    ['Scheduled ahead', scheduled, 'Upcoming work']
  ];
  container.innerHTML = metrics.map(([label,value,note]) => `<article class="analytics-card"><span>${label}</span><strong>${value}</strong><small>${note}</small></article>`).join('');
}
