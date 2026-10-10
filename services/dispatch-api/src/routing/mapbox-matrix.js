import { rankDrivers } from '../dispatch-engine.js';

const MAX_ROUTED_CANDIDATES = 8;
const DEFAULT_PROFILE = 'mapbox/driving-traffic';

function fairnessPenaltyMinutes(driver, now) {
  const queuePenalty = Math.max(0, Number(driver.queueRank ?? 0)) * 0.25;
  const recentDeclinePenalty = Math.min(Number(driver.recentDeclines ?? 0), 5) * 1.1;
  const idleMinutes = driver.availableSince ? Math.max(0, (now - Number(driver.availableSince)) / 60000) : 0;
  const idleCredit = Math.min(idleMinutes, 60) * 0.03;
  return queuePenalty + recentDeclinePenalty - idleCredit;
}

function fallbackDecision(drivers, pickup, attemptedDriverIds, now) {
  const attempted = new Set(attemptedDriverIds);
  const driver = rankDrivers({ drivers, pickup, now }).find((item) => !attempted.has(item.id)) ?? null;
  return {
    driver,
    routed: false,
    provider: 'haversine',
    profile: null,
    reason: driver ? 'routing_unavailable' : 'no_available_driver'
  };
}

export async function chooseDriverWithRoadEta({
  drivers,
  booking,
  attemptedDriverIds = [],
  now = Date.now(),
  accessToken = process.env.MAPBOX_ACCESS_TOKEN || '',
  profile = process.env.MAPBOX_ROUTING_PROFILE || DEFAULT_PROFILE,
  fetchImpl = fetch,
  timeoutMs = 3500
}) {
  const fallback = fallbackDecision(drivers, booking.pickup, attemptedDriverIds, now);
  if (!fallback.driver || !String(accessToken).trim()) return fallback;

  const attempted = new Set(attemptedDriverIds);
  const shortlist = rankDrivers({ drivers, pickup: booking.pickup, now })
    .filter((driver) => !attempted.has(driver.id))
    .slice(0, MAX_ROUTED_CANDIDATES);
  if (!shortlist.length) return fallback;

  // driving-traffic is capped at 10 coordinates. Eight candidate drivers + pickup
  // leaves headroom and keeps one dispatch decision to one Matrix request.
  const coordinates = [
    ...shortlist.map((driver) => `${Number(driver.location.lng).toFixed(6)},${Number(driver.location.lat).toFixed(6)}`),
    `${Number(booking.pickup.lng).toFixed(6)},${Number(booking.pickup.lat).toFixed(6)}`
  ];
  const pickupIndex = coordinates.length - 1;
  const sources = shortlist.map((_, index) => index).join(';');
  const endpoint = new URL(`https://api.mapbox.com/directions-matrix/v1/${profile}/${coordinates.join(';')}`);
  endpoint.searchParams.set('sources', sources);
  endpoint.searchParams.set('destinations', String(pickupIndex));
  endpoint.searchParams.set('annotations', 'duration,distance');
  endpoint.searchParams.set('access_token', String(accessToken).trim());

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), Math.max(500, Number(timeoutMs) || 3500));
  try {
    const response = await fetchImpl(endpoint, { signal: controller.signal });
    if (!response.ok) return { ...fallback, reason: `routing_http_${response.status}` };
    const matrix = await response.json();
    if (matrix?.code !== 'Ok' || !Array.isArray(matrix.durations)) return { ...fallback, reason: 'routing_invalid_response' };

    const routed = shortlist.map((driver, index) => {
      const etaSeconds = Number(matrix.durations?.[index]?.[0]);
      const distanceMeters = Number(matrix.distances?.[index]?.[0]);
      if (!Number.isFinite(etaSeconds) || etaSeconds < 0) return null;
      const etaMinutes = etaSeconds / 60;
      const roadDistanceKm = Number.isFinite(distanceMeters) && distanceMeters >= 0 ? distanceMeters / 1000 : null;
      const routingScore = etaMinutes + fairnessPenaltyMinutes(driver, now);
      return {
        ...driver,
        etaSeconds: Math.round(etaSeconds),
        roadDistanceKm: roadDistanceKm == null ? null : Number(roadDistanceKm.toFixed(3)),
        routingScore: Number(routingScore.toFixed(3)),
        routingProvider: 'mapbox',
        routingProfile: profile
      };
    }).filter(Boolean).sort((a, b) => a.routingScore - b.routingScore);

    if (!routed.length) return { ...fallback, reason: 'routing_no_routes' };
    return {
      driver: routed[0],
      routed: true,
      provider: 'mapbox',
      profile,
      reason: 'road_eta'
    };
  } catch (error) {
    return { ...fallback, reason: error?.name === 'AbortError' ? 'routing_timeout' : 'routing_error' };
  } finally {
    clearTimeout(timeout);
  }
}

export function routingStatus(env = process.env) {
  return {
    provider: String(env.MAPBOX_ACCESS_TOKEN || '').trim() ? 'mapbox' : 'haversine',
    enabled: Boolean(String(env.MAPBOX_ACCESS_TOKEN || '').trim()),
    profile: String(env.MAPBOX_ROUTING_PROFILE || DEFAULT_PROFILE)
  };
}
