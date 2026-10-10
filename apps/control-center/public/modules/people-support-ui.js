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
  container.innerHTML = tickets.length ? tickets.map((ticket) => `<article class="support-row">
    <div class="support-priority ${escapeHtml(ticket.priority)}"></div>
    <div class="support-main"><div><strong>${escapeHtml(ticket.subject || 'Issue')}</strong><span class="badge ${escapeHtml(ticket.status)}">${escapeHtml(statusLabel(ticket.status))}</span></div><span>${escapeHtml(ticket.category || 'app')} · ${escapeHtml(ago(ticket.created_at || ticket.createdAt))}</span></div>
    <div class="support-actions">${ticket.status !== 'resolved' ? `<button class="text-action" data-resolve-ticket="${escapeHtml(ticket.id)}">Resolve</button>` : '<span class="muted">Resolved</span>'}</div>
  </article>`).join('') : '<div class="empty-state"><strong>No support tickets</strong><span>Driver-reported issues will appear here.</span></div>';
}
