import { ago, escapeHtml, statusLabel } from './format.js';

export function renderCustomers(container, customers = []) {
  if (!container) return;
  container.innerHTML = customers.length ? customers.map((customer) => `<article class="people-row">
    <div class="people-avatar">${escapeHtml(String(customer.display_name || 'G').trim().slice(0,1).toUpperCase())}</div>
    <div class="people-main"><strong>${escapeHtml(customer.display_name || 'Guest')}</strong><span>${escapeHtml(customer.phone_e164 || 'No phone')}</span></div>
    <div class="people-side"><strong>${Number(customer.total_bookings || 0)}</strong><span>bookings</span></div>
    <div class="people-side"><span>${escapeHtml(ago(customer.last_booking_at))}</span><small>last booking</small></div>
  </article>`).join('') : '<div class="empty-state"><strong>No customer records yet</strong><span>Customers are created automatically from real bookings with a phone number.</span></div>';
}

export function renderSupport(container, tickets = []) {
  if (!container) return;
  const sorted = [...tickets].sort((a, b) => {
    const rank = { urgent: 0, high: 1, normal: 2, low: 3 };
    return (rank[a.priority] ?? 9) - (rank[b.priority] ?? 9) || new Date(b.created_at || b.createdAt || 0) - new Date(a.created_at || a.createdAt || 0);
  });
  container.innerHTML = sorted.length ? sorted.map((ticket) => `<article class="support-row ${ticket.category === 'safety' && ticket.status !== 'resolved' ? 'safety-ticket' : ''}">
    <div class="support-priority ${escapeHtml(ticket.priority)}"></div>
    <div class="support-main"><div><strong>${escapeHtml(ticket.subject || 'Issue')}</strong><span class="badge ${escapeHtml(ticket.status)}">${escapeHtml(statusLabel(ticket.status))}</span></div><span>${escapeHtml(ticket.category || 'app')} · ${escapeHtml(ago(ticket.created_at || ticket.createdAt))}</span></div>
    <div class="support-actions">${ticket.status !== 'resolved' ? `<button class="text-action" data-resolve-ticket="${escapeHtml(ticket.id)}">Resolve</button>` : '<span class="muted">Resolved</span>'}</div>
  </article>`).join('') : '<div class="empty-state"><strong>No support tickets</strong><span>Driver-reported issues will appear here.</span></div>';
}

export function renderSafetyBanner(container, tickets = []) {
  if (!container) return;
  const open = tickets.filter((ticket) => ticket.category === 'safety' && ticket.status !== 'resolved');
  if (!open.length) {
    container.hidden = true;
    container.innerHTML = '';
    return;
  }
  const latest = [...open].sort((a, b) => new Date(b.created_at || b.createdAt || 0) - new Date(a.created_at || a.createdAt || 0))[0];
  container.hidden = false;
  container.innerHTML = `<button type="button" class="safety-banner-button" data-open-page="support">
    <span class="safety-pulse" aria-hidden="true"></span>
    <span class="safety-banner-copy"><strong>${open.length === 1 ? 'Driver safety alert' : `${open.length} driver safety alerts`}</strong><small>${escapeHtml(latest.subject || 'Urgent field alert')} · ${escapeHtml(ago(latest.created_at || latest.createdAt))}</small></span>
    <span class="safety-banner-action">Open safety</span>
  </button>`;
}
