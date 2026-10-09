import { randomUUID } from 'node:crypto';

const DEFAULT_LEASE_MS = 12_000;

export function createPttFloor({ leaseMs = DEFAULT_LEASE_MS, now = () => Date.now() } = {}) {
  const floors = new Map();

  function keyFor(context) {
    const tenantId = String(context.membership?.tenant_id ?? '');
    if (!tenantId) throw new Error('PTT tenant is missing.');
    return tenantId;
  }

  function holderFor(context) {
    const userId = String(context.user?.id ?? '');
    const role = String(context.membership?.role ?? '');
    if (!userId || !role) throw new Error('PTT participant identity is missing.');
    return `${role}-${userId}`;
  }

  function current(context) {
    const key = keyFor(context);
    const value = floors.get(key);
    if (value && value.expiresAt <= now()) {
      floors.delete(key);
      return null;
    }
    return value ?? null;
  }

  function acquire(context) {
    const key = keyFor(context);
    const holder = holderFor(context);
    const existing = current(context);
    if (existing && existing.holder !== holder) {
      return { granted: false, holder: existing.holder, expiresAt: existing.expiresAt };
    }
    const leaseToken = existing?.leaseToken ?? randomUUID();
    const lease = { holder, leaseToken, expiresAt: now() + leaseMs };
    floors.set(key, lease);
    return { granted: true, ...lease };
  }

  function heartbeat(context, leaseToken) {
    const key = keyFor(context);
    const holder = holderFor(context);
    const existing = current(context);
    if (!existing || existing.holder !== holder || existing.leaseToken !== leaseToken) {
      return { granted: false };
    }
    existing.expiresAt = now() + leaseMs;
    floors.set(key, existing);
    return { granted: true, ...existing };
  }

  function release(context, leaseToken) {
    const key = keyFor(context);
    const holder = holderFor(context);
    const existing = current(context);
    if (!existing || existing.holder !== holder || existing.leaseToken !== leaseToken) return { released: false };
    floors.delete(key);
    return { released: true };
  }

  return { acquire, heartbeat, release, current };
}
