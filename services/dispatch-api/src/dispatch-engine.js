export const DRIVER_STATUS = Object.freeze({
  AVAILABLE: 'available',
  OFFERED: 'offered',
  BUSY: 'busy',
  UNAVAILABLE: 'unavailable',
  OFFLINE: 'offline'
});

export const BOOKING_STATUS = Object.freeze({
  SCHEDULED: 'scheduled',
  PENDING: 'pending',
  OFFERING: 'offering',
  ASSIGNED: 'assigned',
  ARRIVED: 'arrived',
  IN_PROGRESS: 'in_progress',
  COMPLETED: 'completed',
  CANCELLED: 'cancelled',
  NO_SHOW: 'no_show',
  UNFULFILLED: 'unfulfilled'
});

export function haversineKm(a, b) {
  const R = 6371;
  const toRad = (v) => (v * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const s1 = Math.sin(dLat / 2);
  const s2 = Math.sin(dLng / 2);
  const h = s1 * s1 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * s2 * s2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

export function rankDrivers({ drivers, pickup, now = Date.now() }) {
  return drivers
    .filter((d) => d.status === DRIVER_STATUS.AVAILABLE && d.location)
    .map((d) => {
      const distanceKm = haversineKm(d.location, pickup);
      const queuePenalty = Math.max(0, Number(d.queueRank ?? 0)) * 0.12;
      const recentDeclinePenalty = Math.min(Number(d.recentDeclines ?? 0), 5) * 0.55;
      const idleMinutes = d.availableSince ? Math.max(0, (now - d.availableSince) / 60000) : 0;
      const idleCredit = Math.min(idleMinutes, 60) * 0.015;
      const score = distanceKm + queuePenalty + recentDeclinePenalty - idleCredit;
      return { ...d, distanceKm, dispatchScore: Number(score.toFixed(3)) };
    })
    .sort((a, b) => a.dispatchScore - b.dispatchScore);
}

export function chooseNextDriver({ drivers, booking, attemptedDriverIds = [] }) {
  const attempted = new Set(attemptedDriverIds);
  return rankDrivers({ drivers, pickup: booking.pickup }).find((d) => !attempted.has(d.id)) ?? null;
}

function parseScheduledFor(value) {
  if (value === undefined || value === null || value === '') return null;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error('Scheduled pickup time is invalid.');
  return date.toISOString();
}

export function parseStructuredTaxiRequest(input = {}) {
  const pickup = input.pickup;
  const destination = input.destination;
  const passengerName = String(input.passengerName ?? '').trim();
  const passengerPhone = String(input.passengerPhone ?? '').trim().slice(0, 32);
  const passengers = Math.max(1, Math.min(8, Number(input.passengers ?? 1) || 1));
  const notes = String(input.notes ?? '').trim().slice(0, 300);
  const scheduledFor = parseScheduledFor(input.scheduledFor);

  if (!pickup || !Number.isFinite(Number(pickup.lat)) || !Number.isFinite(Number(pickup.lng))) {
    throw new Error('A valid pickup location is required.');
  }
  if (!destination || !String(destination.label ?? '').trim()) {
    throw new Error('A destination is required.');
  }

  return {
    passengerName: passengerName || 'Guest',
    passengerPhone,
    passengers,
    notes,
    scheduledFor,
    pickup: {
      label: String(pickup.label ?? 'Shared location').trim().slice(0, 160),
      lat: Number(pickup.lat),
      lng: Number(pickup.lng)
    },
    destination: {
      label: String(destination.label).trim().slice(0, 160),
      lat: Number.isFinite(Number(destination.lat)) ? Number(destination.lat) : null,
      lng: Number.isFinite(Number(destination.lng)) ? Number(destination.lng) : null
    }
  };
}
