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
      setNightMode: async (v) => memory.setNightMode(v),
      setDriverStatus: async (id, status) => memory.setDriverStatus(id, status),
      updateDriverLocation: async (id, location) => memory.updateDriverLocation(id, location),
      createBooking: async (input) => memory.createBooking(input),
      respondToOffer: async (input) => memory.respondToOffer(input),
      expireOffers: async (now) => memory.expireOffers(now),
      subscribe: memory.subscribe,
      sessionContext: async () => demoContext,
      login: async () => ({ accessToken: 'demo', refreshToken: 'demo', expiresIn: 3600, ...demoContext }),
      refresh: async () => ({ accessToken: 'demo', refreshToken: 'demo', expiresIn: 3600, ...demoContext })
    }
  };
}
