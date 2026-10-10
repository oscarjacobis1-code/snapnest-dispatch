import * as memory from './store.js';
import { runtimeConfig } from './config.js';
import { createSupabaseStore } from './supabase-store.js';

export function createRuntimeStore(env = process.env) {
  const config = runtimeConfig(env);
  if (config.persistent) return { config, store: createSupabaseStore(config) };
  const demoContext = { user: { id: 'demo', email: 'demo@snapnest.local' }, membership: { role: 'admin' }, driver: null };

  async function reserveScheduledBooking({ bookingId, driverId }) {
    const booking = memory.getBooking(bookingId);
    const driver = memory.state.drivers.find((item) => item.id === driverId);
    if (!driver) throw new Error('Driver not found.');
    if (booking.status !== 'scheduled') throw new Error('Only scheduled bookings can be reserved.');
    booking.reservedDriverId = driverId;
    memory.emit('booking.reserved', { bookingId, driverId });
    return booking;
  }

  async function clearScheduledReservation({ bookingId }) {
    const booking = memory.getBooking(bookingId);
    if (booking.status !== 'scheduled') throw new Error('Only scheduled bookings can change reservations.');
    const driverId = booking.reservedDriverId || null;
    booking.reservedDriverId = null;
    memory.emit('booking.reservation_cleared', { bookingId, driverId });
    return booking;
  }

  async function driverUpcoming(driverId, limit = 20) {
    return memory.state.bookings
      .filter((booking) => booking.status === 'scheduled' && booking.reservedDriverId === driverId && booking.scheduledFor && new Date(booking.scheduledFor).getTime() > Date.now())
      .sort((a, b) => new Date(a.scheduledFor) - new Date(b.scheduledFor))
      .slice(0, Math.max(1, Math.min(50, Number(limit) || 20)));
  }

  async function activateScheduledBookings(now = Date.now(), leadMinutes = 15) {
    const threshold = now + leadMinutes * 60_000;
    const reservedActivated = [];
    for (const booking of memory.state.bookings) {
      if (booking.status !== 'scheduled' || !booking.scheduledFor || new Date(booking.scheduledFor).getTime() > threshold || !booking.reservedDriverId) continue;
      const driver = memory.state.drivers.find((item) => item.id === booking.reservedDriverId);
      if (driver?.status !== 'available') continue;
      booking.status = 'pending';
      memory.emit('booking.scheduled_ready', { bookingId: booking.id, reservedDriverId: booking.reservedDriverId });
      memory.assignBooking({ bookingId: booking.id, driverId: booking.reservedDriverId });
      memory.emit('booking.reservation_assigned', { bookingId: booking.id, driverId: booking.reservedDriverId });
      reservedActivated.push(booking);
    }
    return [...reservedActivated, ...memory.activateScheduledBookings(now, leadMinutes)];
  }

  return {
    config,
    store: {
      mode: 'memory',
      publicState: async () => ({ mode: 'memory', ...memory.publicState() }),
      getBooking: async (id) => memory.getBooking(id),
      activeOfferForDriver: async (driverId) => memory.publicState().bookings.find((b) => b.currentOfferDriverId === driverId) ?? null,
      setNightMode: async (v) => memory.setNightMode(v),
      setDriverStatus: async (id, status) => memory.setDriverStatus(id, status),
      updateDriverLocation: async (id, location) => memory.updateDriverLocation(id, location),
      createBooking: async (input) => memory.createBooking(input),
      activateScheduledBookings,
      reserveScheduledBooking,
      clearScheduledReservation,
      driverUpcoming,
      assignBooking: async (input) => memory.assignBooking(input),
      requeueBooking: async (input) => memory.requeueBooking(input),
      cancelBooking: async (input) => memory.cancelBooking(input),
      driverHistory: async (id, limit) => memory.driverHistory(id, limit),
      listCustomers: async (limit) => memory.listCustomers(limit),
      createSupportTicket: async (input) => memory.createSupportTicket(input),
      listSupportTickets: async (input) => memory.listSupportTickets(input),
      resolveSupportTicket: async (input) => memory.resolveSupportTicket(input),
      availableDrivers: async () => memory.publicState().drivers.filter((d) => d.status === 'available'),
      respondToOffer: async (input) => memory.respondToOffer(input),
      arriveTrip: async (input) => memory.arriveTrip(input),
      startTrip: async (input) => memory.startTrip(input),
      completeTrip: async (input) => memory.completeTrip(input),
      expireOffers: async (now) => memory.expireOffers(now),
      subscribe: memory.subscribe,
      sessionContext: async () => demoContext,
      login: async () => ({ accessToken: 'demo', refreshToken: 'demo', expiresIn: 3600, ...demoContext }),
      refresh: async () => ({ accessToken: 'demo', refreshToken: 'demo', expiresIn: 3600, ...demoContext })
    }
  };
}
