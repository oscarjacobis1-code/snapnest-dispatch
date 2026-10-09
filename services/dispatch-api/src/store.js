import { BOOKING_STATUS, DRIVER_STATUS, chooseNextDriver } from './dispatch-engine.js';

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
function offerNextDriver(booking) {
  const next = chooseNextDriver({ drivers: state.drivers, booking, attemptedDriverIds: booking.attemptedDriverIds });
  if (!next) { booking.status = BOOKING_STATUS.UNFULFILLED; booking.currentOfferDriverId = null; emit('booking.unfulfilled', { bookingId: booking.id }); return null; }
  const driver = state.drivers.find((d) => d.id === next.id); driver.status = DRIVER_STATUS.OFFERED;
  booking.status = BOOKING_STATUS.OFFERING; booking.currentOfferDriverId = driver.id; booking.attemptedDriverIds.push(driver.id); booking.offerExpiresAt = Date.now() + 20_000;
  emit('booking.offered', { bookingId: booking.id, driverId: driver.id, distanceKm: next.distanceKm, score: next.dispatchScore }); return driver;
}
export function createBooking(input) {
  const booking = { id: `B${++sequence}`, tenantId: 'demo-base', source: input.source ?? 'web', passengerName: input.passengerName, passengerPhone: input.passengerPhone ?? '', passengers: input.passengers, notes: input.notes, pickup: input.pickup, destination: input.destination, status: BOOKING_STATUS.PENDING, createdAt: new Date().toISOString(), assignedAt: null, startedAt: null, completedAt: null, assignedDriverId: null, currentOfferDriverId: null, attemptedDriverIds: [] };
  state.bookings.unshift(booking); emit('booking.created', { bookingId: booking.id, source: booking.source }); offerNextDriver(booking); return booking;
}
export function respondToOffer({ bookingId, driverId, accept }) {
  const booking = state.bookings.find((b) => b.id === bookingId); if (!booking) throw new Error('Booking not found.');
  if (booking.currentOfferDriverId !== driverId) throw new Error('This offer is no longer active.');
  const driver = state.drivers.find((d) => d.id === driverId); if (!driver) throw new Error('Driver not found.');
  if (accept) { booking.status = BOOKING_STATUS.ASSIGNED; booking.assignedDriverId = driver.id; booking.currentOfferDriverId = null; booking.assignedAt = new Date().toISOString(); driver.status = DRIVER_STATUS.BUSY; emit('booking.assigned', { bookingId, driverId }); return booking; }
  driver.status = DRIVER_STATUS.AVAILABLE; driver.recentDeclines = (driver.recentDeclines ?? 0) + 1; driver.availableSince = Date.now(); booking.currentOfferDriverId = null; emit('booking.declined', { bookingId, driverId }); offerNextDriver(booking); return booking;
}
export function startTrip({ bookingId, driverId }) {
  const booking = state.bookings.find((b) => b.id === bookingId); if (!booking) throw new Error('Booking not found.');
  if (booking.assignedDriverId !== driverId) throw new Error('Booking is not assigned to this driver.');
  if (![BOOKING_STATUS.ASSIGNED, BOOKING_STATUS.IN_PROGRESS].includes(booking.status)) throw new Error('Booking cannot be started.');
  booking.status = BOOKING_STATUS.IN_PROGRESS;
  booking.startedAt = booking.startedAt || new Date().toISOString();
  const driver = state.drivers.find((d) => d.id === driverId); if (driver) driver.status = DRIVER_STATUS.BUSY;
  emit('booking.started', { bookingId, driverId });
  return booking;
}
export function completeTrip({ bookingId, driverId }) {
  const booking = state.bookings.find((b) => b.id === bookingId); if (!booking) throw new Error('Booking not found.');
  if (booking.assignedDriverId !== driverId) throw new Error('Booking is not assigned to this driver.');
  if (![BOOKING_STATUS.ASSIGNED, BOOKING_STATUS.IN_PROGRESS].includes(booking.status)) throw new Error('Booking cannot be completed.');
  booking.status = BOOKING_STATUS.COMPLETED;
  booking.startedAt = booking.startedAt || new Date().toISOString();
  booking.completedAt = new Date().toISOString();
  const driver = state.drivers.find((d) => d.id === driverId);
  if (driver) {
    driver.status = DRIVER_STATUS.AVAILABLE;
    driver.availableSince = Date.now();
    driver.recentDeclines = 0;
    driver.queueRank = Math.max(0, ...state.drivers.map((d) => Number(d.queueRank ?? 0))) + 1;
  }
  emit('booking.completed', { bookingId, driverId });
  return booking;
}
export function expireOffers(nowMs = Date.now()) {
  for (const booking of state.bookings) {
    if (booking.status !== BOOKING_STATUS.OFFERING || !booking.offerExpiresAt || booking.offerExpiresAt > nowMs) continue;
    const driverId = booking.currentOfferDriverId; const driver = state.drivers.find((d) => d.id === driverId);
    if (driver?.status === DRIVER_STATUS.OFFERED) { driver.status = DRIVER_STATUS.AVAILABLE; driver.availableSince = Date.now(); }
    booking.currentOfferDriverId = null; emit('booking.offer_expired', { bookingId: booking.id, driverId }); offerNextDriver(booking);
  }
}
