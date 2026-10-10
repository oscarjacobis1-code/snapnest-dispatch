import * as memory from './store.js';
import { runtimeConfig } from './config.js';
import { createSupabaseStore } from './supabase-store.js';

export function createRuntimeStore(env = process.env) {
  const config = runtimeConfig(env);
  if (config.persistent) return { config, store: createSupabaseStore(config) };
  const demoContext = { user: { id: 'demo', email: 'demo@snapnest.local' }, membership: { role: 'admin' }, driver: null };
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
      activateScheduledBookings: async (now, leadMinutes) => memory.activateScheduledBookings(now, leadMinutes),
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
