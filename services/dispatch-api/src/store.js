import { BOOKING_STATUS, DRIVER_STATUS, chooseNextDriver, parseScheduledFor } from './dispatch-engine.js';

const now = Date.now();
export const state = {
  nightMode: false,
  drivers: [
    { id: 'd1', name: 'Car 08', vehicle: 'HC 3088', status: DRIVER_STATUS.AVAILABLE, location: { lat: 6.8208, lng: -58.1551 }, queueRank: 1, recentDeclines: 0, availableSince: now - 42 * 60000 },
    { id: 'd2', name: 'Car 03', vehicle: 'HD 1703', status: DRIVER_STATUS.AVAILABLE, location: { lat: 6.8149, lng: -58.1515 }, queueRank: 2, recentDeclines: 0, availableSince: now - 25 * 60000 },
    { id: 'd3', name: 'Car 12', vehicle: 'HC 9212', status: DRIVER_STATUS.BUSY, location: { lat: 6.8072, lng: -58.1666 }, queueRank: 3, recentDeclines: 0, availableSince: null },
    { id: 'd4', name: 'Car 15', vehicle: 'HB 4515', status: DRIVER_STATUS.AVAILABLE, location: { lat: 6.8263, lng: -58.1624 }, queueRank: 4, recentDeclines: 1, availableSince: now - 8 * 60000 }
  ],
  bookings: [],
  customers: [],
  supportTickets: [],
  communications: [],
  events: []
};
let sequence = 1000;
const listeners = new Set();
export function subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); }
export function emit(type, payload) {
  const event = { id: ++sequence, type, at: new Date().toISOString(), payload };
  state.events.unshift(event); state.events = state.events.slice(0, 80);
  for (const listener of listeners) listener(event);
  return event;
}
export function publicState() { return { nightMode: state.nightMode, drivers: state.drivers, bookings: state.bookings, events: state.events.slice(0, 25) }; }
export function recordWhatsAppMessage(message) {
  if (state.communications.some((item) => item.external_id === message.externalId)) return false;
  state.communications.unshift({ id: `W${++sequence}`, channel: 'whatsapp', direction: 'inbound', external_id: message.externalId, payload: message, created_at: new Date().toISOString() });
  state.communications = state.communications.slice(0, 200);
  emit('whatsapp.received', { sender: message.sender, type: message.type });
  return true;
}
export function listWhatsAppMessages(limit = 50) { return state.communications.slice(0, Math.max(1, Math.min(100, Number(limit) || 50))); }
export function getBooking(id) { const booking = state.bookings.find((b) => b.id === id); if (!booking) throw new Error('Booking not found.'); return booking; }
export function setNightMode(enabled) { state.nightMode = Boolean(enabled); emit('night_mode.changed', { enabled: state.nightMode }); return state.nightMode; }
export function setDriverStatus(driverId, status) {
  const driver = state.drivers.find((d) => d.id === driverId); if (!driver) throw new Error('Driver not found.');
  const allowed = new Set(Object.values(DRIVER_STATUS)); if (!allowed.has(status)) throw new Error('Invalid driver status.');
  driver.status = status; driver.availableSince = status === DRIVER_STATUS.AVAILABLE ? Date.now() : null;
  emit('driver.status', { driverId, status }); return driver;
}
export function updateDriverLocation(driverId, location) {
  const driver = state.drivers.find((d) => d.id === driverId); if (!driver) throw new Error('Driver not found.');
  driver.location = { lat: Number(location.lat), lng: Number(location.lng), accuracyM: Number(location.accuracyM ?? 0), capturedAt: new Date().toISOString() };
  emit('driver.location', { driverId, ...driver.location }); return driver.location;
}
function upsertCustomer(input) {
  const phone = String(input.passengerPhone || '').trim(); if (!phone) return null;
  let customer = state.customers.find((c) => c.phone_e164 === phone);
  if (!customer) {
    customer = { id: `C${++sequence}`, display_name: input.passengerName || 'Guest', phone_e164: phone, notes: '', total_bookings: 0, last_booking_at: null };
    state.customers.push(customer);
  }
  customer.display_name = input.passengerName || customer.display_name;
  customer.total_bookings += 1;
  customer.last_booking_at = new Date().toISOString();
  return customer;
}
function offerNextDriver(booking) {
  const next = chooseNextDriver({ drivers: state.drivers, booking, attemptedDriverIds: booking.attemptedDriverIds });
  if (!next) { booking.status = BOOKING_STATUS.UNFULFILLED; booking.currentOfferDriverId = null; emit('booking.unfulfilled', { bookingId: booking.id }); return null; }
  const driver = state.drivers.find((d) => d.id === next.id); driver.status = DRIVER_STATUS.OFFERED;
  booking.status = BOOKING_STATUS.OFFERING; booking.currentOfferDriverId = driver.id; booking.attemptedDriverIds.push(driver.id); booking.offerExpiresAt = Date.now() + 20_000;
  emit('booking.offered', { bookingId: booking.id, driverId: driver.id, distanceKm: next.distanceKm, score: next.dispatchScore }); return driver;
}
export function createBooking(input) {
  const scheduledFor = parseScheduledFor(input.scheduledFor);
  const scheduled = Boolean(scheduledFor);
  const booking = {
    id: `B${++sequence}`, tenantId: 'demo-base', source: input.source ?? 'web', passengerName: input.passengerName,
    passengerPhone: input.passengerPhone ?? '', passengers: input.passengers, notes: input.notes, pickup: input.pickup,
    destination: input.destination, status: scheduled ? BOOKING_STATUS.SCHEDULED : BOOKING_STATUS.PENDING,
    createdAt: new Date().toISOString(), scheduledFor,
    assignedAt: null, arrivedAt: null, startedAt: null, completedAt: null, cancelledAt: null,
    cancellationReason: null, cancellationCode: null, cancelledByRole: null, noShowAt: null, reassignCount: 0,
    assignedDriverId: null, currentOfferDriverId: null, attemptedDriverIds: []
  };
  state.bookings.unshift(booking); upsertCustomer(input); emit('booking.created', { bookingId: booking.id, source: booking.source, scheduledFor: booking.scheduledFor });
  if (!scheduled) offerNextDriver(booking);
  return booking;
}
export function rescheduleBooking({ bookingId, scheduledFor }) {
  const next = parseScheduledFor(scheduledFor);
  if (!next) throw new Error('A scheduled pickup time is required.');
  const booking = getBooking(bookingId);
  if (booking.status !== BOOKING_STATUS.SCHEDULED) throw new Error('Only scheduled bookings can be rescheduled.');
  const previousScheduledFor = booking.scheduledFor;
  booking.scheduledFor = next;
  emit('booking.rescheduled', { bookingId, previousScheduledFor, scheduledFor: next, reservedDriverId: booking.reservedDriverId || null });
  return booking;
}
export function activateScheduledBookings(nowMs = Date.now(), leadMinutes = 15) {
  const threshold = nowMs + leadMinutes * 60_000; const activated = [];
  for (const booking of state.bookings) {
    if (booking.status !== BOOKING_STATUS.SCHEDULED || !booking.scheduledFor || new Date(booking.scheduledFor).getTime() > threshold) continue;
    booking.status = BOOKING_STATUS.PENDING; emit('booking.scheduled_ready', { bookingId: booking.id }); offerNextDriver(booking); activated.push(booking);
  }
  return activated;
}
export function respondToOffer({ bookingId, driverId, accept }) {
  const booking = getBooking(bookingId);
  if (booking.currentOfferDriverId !== driverId) throw new Error('This offer is no longer active.');
  const driver = state.drivers.find((d) => d.id === driverId); if (!driver) throw new Error('Driver not found.');
  if (accept) { booking.status = BOOKING_STATUS.ASSIGNED; booking.assignedDriverId = driver.id; booking.currentOfferDriverId = null; booking.assignedAt = new Date().toISOString(); driver.status = DRIVER_STATUS.BUSY; emit('booking.assigned', { bookingId, driverId }); return booking; }
  driver.status = DRIVER_STATUS.AVAILABLE; driver.recentDeclines = (driver.recentDeclines ?? 0) + 1; driver.availableSince = Date.now(); booking.currentOfferDriverId = null; emit('booking.declined', { bookingId, driverId }); offerNextDriver(booking); return booking;
}
export function assignBooking({ bookingId, driverId }) {
  const booking = getBooking(bookingId); const driver = state.drivers.find((d) => d.id === driverId); if (!driver) throw new Error('Driver not found.');
  if ([BOOKING_STATUS.COMPLETED, BOOKING_STATUS.CANCELLED, BOOKING_STATUS.NO_SHOW, BOOKING_STATUS.IN_PROGRESS].includes(booking.status)) throw new Error('Booking cannot be assigned in its current state.');
  if (booking.status === BOOKING_STATUS.SCHEDULED) throw new Error('Scheduled booking is not active yet.');
  if (booking.assignedDriverId !== driverId && driver.status !== DRIVER_STATUS.AVAILABLE) throw new Error('Driver is not available.');
  const oldDriver = state.drivers.find((d) => d.id === booking.assignedDriverId);
  if (oldDriver && oldDriver.id !== driverId) { oldDriver.status = DRIVER_STATUS.AVAILABLE; oldDriver.availableSince = Date.now(); booking.reassignCount += 1; }
  const offered = state.drivers.find((d) => d.id === booking.currentOfferDriverId); if (offered?.status === DRIVER_STATUS.OFFERED) { offered.status = DRIVER_STATUS.AVAILABLE; offered.availableSince = Date.now(); }
  booking.currentOfferDriverId = null; booking.assignedDriverId = driverId; booking.assignedAt = new Date().toISOString(); booking.arrivedAt = null; booking.status = BOOKING_STATUS.ASSIGNED; driver.status = DRIVER_STATUS.BUSY; driver.availableSince = null;
  emit('booking.manual_assigned', { bookingId, driverId }); return booking;
}
export function requeueBooking({ bookingId }) {
  const booking = getBooking(bookingId);
  if ([BOOKING_STATUS.COMPLETED, BOOKING_STATUS.IN_PROGRESS].includes(booking.status)) throw new Error('Booking cannot be requeued.');
  const oldDriver = state.drivers.find((d) => d.id === booking.assignedDriverId); if (oldDriver?.status === DRIVER_STATUS.BUSY) { oldDriver.status = DRIVER_STATUS.AVAILABLE; oldDriver.availableSince = Date.now(); }
  const offered = state.drivers.find((d) => d.id === booking.currentOfferDriverId); if (offered?.status === DRIVER_STATUS.OFFERED) { offered.status = DRIVER_STATUS.AVAILABLE; offered.availableSince = Date.now(); }
  booking.status = BOOKING_STATUS.PENDING; booking.assignedDriverId = null; booking.currentOfferDriverId = null; booking.assignedAt = null; booking.arrivedAt = null; booking.cancelledAt = null; booking.cancellationReason = null; booking.cancellationCode = null; booking.noShowAt = null;
  emit('booking.requeued', { bookingId }); offerNextDriver(booking); return booking;
}
export function cancelBooking({ bookingId, reason, code, actorRole, noShow = false }) {
  const booking = getBooking(bookingId); if (booking.status === BOOKING_STATUS.COMPLETED) throw new Error('Completed booking cannot be cancelled.');
  const oldDriver = state.drivers.find((d) => d.id === booking.assignedDriverId); if (oldDriver?.status === DRIVER_STATUS.BUSY) { oldDriver.status = DRIVER_STATUS.AVAILABLE; oldDriver.availableSince = Date.now(); }
  const offered = state.drivers.find((d) => d.id === booking.currentOfferDriverId); if (offered?.status === DRIVER_STATUS.OFFERED) { offered.status = DRIVER_STATUS.AVAILABLE; offered.availableSince = Date.now(); }
  booking.status = noShow ? BOOKING_STATUS.NO_SHOW : BOOKING_STATUS.CANCELLED; booking.cancelledAt = new Date().toISOString(); booking.noShowAt = noShow ? booking.cancelledAt : null; booking.cancellationReason = reason || null; booking.cancellationCode = code || null; booking.cancelledByRole = actorRole || null; booking.currentOfferDriverId = null;
  emit(noShow ? 'booking.no_show' : 'booking.cancelled', { bookingId, reason, code, actorRole }); return booking;
}
export function arriveTrip({ bookingId, driverId }) {
  const booking = getBooking(bookingId); if (booking.assignedDriverId !== driverId) throw new Error('Booking is not assigned to this driver.');
  if (![BOOKING_STATUS.ASSIGNED, BOOKING_STATUS.ARRIVED].includes(booking.status)) throw new Error('Booking cannot be marked arrived.');
  booking.status = BOOKING_STATUS.ARRIVED; booking.arrivedAt = booking.arrivedAt || new Date().toISOString();
  const driver = state.drivers.find((d) => d.id === driverId); if (driver) driver.status = DRIVER_STATUS.BUSY;
  emit('booking.arrived', { bookingId, driverId }); return booking;
}
export function startTrip({ bookingId, driverId }) {
  const booking = getBooking(bookingId); if (booking.assignedDriverId !== driverId) throw new Error('Booking is not assigned to this driver.');
  if (![BOOKING_STATUS.ASSIGNED, BOOKING_STATUS.ARRIVED, BOOKING_STATUS.IN_PROGRESS].includes(booking.status)) throw new Error('Booking cannot be started.');
  booking.status = BOOKING_STATUS.IN_PROGRESS; booking.startedAt = booking.startedAt || new Date().toISOString();
  const driver = state.drivers.find((d) => d.id === driverId); if (driver) driver.status = DRIVER_STATUS.BUSY;
  emit('booking.started', { bookingId, driverId }); return booking;
}
export function completeTrip({ bookingId, driverId }) {
  const booking = getBooking(bookingId); if (booking.assignedDriverId !== driverId) throw new Error('Booking is not assigned to this driver.');
  if (![BOOKING_STATUS.ASSIGNED, BOOKING_STATUS.ARRIVED, BOOKING_STATUS.IN_PROGRESS].includes(booking.status)) throw new Error('Booking cannot be completed.');
  booking.status = BOOKING_STATUS.COMPLETED; booking.startedAt = booking.startedAt || new Date().toISOString(); booking.completedAt = new Date().toISOString();
  const driver = state.drivers.find((d) => d.id === driverId);
  if (driver) { driver.status = DRIVER_STATUS.AVAILABLE; driver.availableSince = Date.now(); driver.recentDeclines = 0; driver.queueRank = Math.max(0, ...state.drivers.map((d) => Number(d.queueRank ?? 0))) + 1; }
  emit('booking.completed', { bookingId, driverId }); return booking;
}
export function driverHistory(driverId, limit = 30) { return state.bookings.filter((b) => b.assignedDriverId === driverId && [BOOKING_STATUS.COMPLETED, BOOKING_STATUS.CANCELLED, BOOKING_STATUS.NO_SHOW].includes(b.status)).slice(0, Math.max(1, Math.min(100, Number(limit) || 30))); }
export function listCustomers(limit = 100) { return [...state.customers].sort((a,b) => new Date(b.last_booking_at || 0) - new Date(a.last_booking_at || 0)).slice(0, Math.max(1, Math.min(250, Number(limit) || 100))); }
export function createSupportTicket({ userId, driverId, bookingId, category='app', priority='normal', subject, description, attachmentUrl }) {
  if (!String(subject || '').trim() || !String(description || '').trim()) throw new Error('Ticket subject and description are required.');
  const ticket = { id: `T${++sequence}`, tenant_id: 'demo-base', created_by_user_id: userId || null, driver_id: driverId || null, booking_id: bookingId || null, category, priority, subject: String(subject).trim(), description: String(description).trim(), attachment_url: attachmentUrl || null, status: 'open', resolution_note: null, resolved_at: null, created_at: new Date().toISOString(), updated_at: new Date().toISOString() };
  state.supportTickets.unshift(ticket); emit('support.created', { ticketId: ticket.id, driverId: driverId || null, priority }); return ticket;
}
export function listSupportTickets({ driverId = null, limit = 100 } = {}) { return state.supportTickets.filter((t) => !driverId || t.driver_id === driverId).slice(0, Math.max(1, Math.min(250, Number(limit) || 100))); }
export function resolveSupportTicket({ ticketId, resolutionNote }) { const ticket = state.supportTickets.find((t) => t.id === ticketId); if (!ticket) throw new Error('Support ticket not found.'); ticket.status='resolved'; ticket.resolution_note=String(resolutionNote || '').trim() || null; ticket.resolved_at=new Date().toISOString(); ticket.updated_at=ticket.resolved_at; emit('support.resolved',{ticketId}); return ticket; }
export function expireOffers(nowMs = Date.now()) {
  for (const booking of state.bookings) {
    if (booking.status !== BOOKING_STATUS.OFFERING || !booking.offerExpiresAt || booking.offerExpiresAt > nowMs) continue;
    const driverId = booking.currentOfferDriverId; const driver = state.drivers.find((d) => d.id === driverId);
    if (driver?.status === DRIVER_STATUS.OFFERED) { driver.status = DRIVER_STATUS.AVAILABLE; driver.availableSince = Date.now(); }
    booking.currentOfferDriverId = null; emit('booking.offer_expired', { bookingId: booking.id, driverId }); offerNextDriver(booking);
  }
}
