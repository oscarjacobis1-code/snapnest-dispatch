import { BOOKING_STATUS, DRIVER_STATUS, chooseNextDriver } from './dispatch-engine.js';
import { SupabaseRest } from './supabase-rest.js';
import { createOperationsModule } from './modules/operations.js';

const first = (value) => Array.isArray(value) ? value[0] : value;
const ms = (value) => value ? new Date(value).getTime() : null;

export function createSupabaseStore(config) {
  const db = new SupabaseRest({
    url: config.supabaseUrl,
    secretKey: config.supabaseSecretKey,
    publishableKey: config.supabasePublishableKey
  });
  const listeners = new Set();
  let tenantCache = null;

  async function tenant() {
    if (tenantCache) return tenantCache;
    const rows = await db.request('tenants', { query: { slug: `eq.${config.tenantSlug}`, select: 'id,name,slug,brand_config,timezone,night_mode,offer_timeout_seconds', limit: 1 } });
    tenantCache = first(rows);
    if (!tenantCache) throw new Error(`Tenant '${config.tenantSlug}' is not configured.`);
    return tenantCache;
  }

  function notify(event) { for (const listener of listeners) listener(event); }
  async function emit(type, payload) {
    const t = await tenant();
    const rows = await db.request('dispatch_events', { method: 'POST', body: { tenant_id: t.id, event_type: type, payload }, prefer: 'return=representation' });
    const row = first(rows) ?? { id: null, event_type: type, payload, created_at: new Date().toISOString() };
    const event = { id: row.id, type: row.event_type, at: row.created_at, payload: row.payload };
    notify(event); return event;
  }

  async function driverRows() {
    const t = await tenant();
    const [drivers, locations] = await Promise.all([
      db.request('drivers', { query: { tenant_id: `eq.${t.id}`, select: 'id,display_name,vehicle_plate,status,queue_rank,recent_declines,available_since,last_seen_at,auth_user_id', order: 'queue_rank.asc' } }),
      db.request('driver_locations', { query: { tenant_id: `eq.${t.id}`, select: 'driver_id,latitude,longitude,accuracy_m,captured_at' } })
    ]);
    const byDriver = new Map(locations.map((l) => [l.driver_id, l]));
    return drivers.map((d) => {
      const l = byDriver.get(d.id);
      return {
        id: d.id,
        name: d.display_name,
        vehicle: d.vehicle_plate ?? '',
        status: d.status,
        queueRank: d.queue_rank,
        recentDeclines: d.recent_declines,
        availableSince: ms(d.available_since),
        lastSeenAt: d.last_seen_at,
        authUserId: d.auth_user_id,
        location: l ? { lat: Number(l.latitude), lng: Number(l.longitude), accuracyM: l.accuracy_m, capturedAt: l.captured_at } : null
      };
    });
  }

  function mapBooking(row, activeOffer) {
    return {
      id: row.id,
      tenantId: row.tenant_id,
      source: row.source,
      passengerName: row.customer_name || 'Guest',
      passengerPhone: row.customer_phone_e164 || '',
      passengers: row.passengers,
      notes: row.notes || '',
      pickup: { label: row.pickup_label, lat: Number(row.pickup_lat), lng: Number(row.pickup_lng) },
      destination: { label: row.destination_label, lat: row.destination_lat == null ? null : Number(row.destination_lat), lng: row.destination_lng == null ? null : Number(row.destination_lng) },
      status: row.status,
      createdAt: row.created_at,
      scheduledFor: row.scheduled_for,
      reservedDriverId: row.reserved_driver_id || null,
      assignedAt: row.assigned_at,
      arrivedAt: row.arrived_at,
      startedAt: row.started_at,
      completedAt: row.completed_at,
      cancelledAt: row.cancelled_at,
      cancellationReason: row.cancellation_reason,
      cancellationCode: row.cancellation_code,
      cancelledByRole: row.cancelled_by_role,
      noShowAt: row.no_show_at,
      reassignCount: Number(row.reassign_count || 0),
      assignedDriverId: row.assigned_driver_id,
      currentOfferDriverId: activeOffer?.driver_id ?? null,
      offerExpiresAt: activeOffer?.expires_at ? new Date(activeOffer.expires_at).getTime() : null
    };
  }

  async function bookingById(id) {
    const t = await tenant();
    const rows = await db.request('bookings', { query: { id: `eq.${id}`, tenant_id: `eq.${t.id}`, select: '*', limit: 1 } });
    const row = first(rows); if (!row) throw new Error('Booking not found.');
    const offers = await db.request('dispatch_offers', { query: { booking_id: `eq.${id}`, response: 'is.null', select: 'driver_id,expires_at', order: 'offered_at.desc', limit: 1 } });
    return mapBooking(row, first(offers));
  }

  async function activeOfferForDriver(driverId) {
    const t = await tenant();
    const offers = await db.request('dispatch_offers', {
      query: {
        tenant_id: `eq.${t.id}`,
        driver_id: `eq.${driverId}`,
        response: 'is.null',
        expires_at: `gt.${new Date().toISOString()}`,
        select: 'booking_id,driver_id,expires_at,offered_at',
        order: 'offered_at.desc',
        limit: 1
      }
    });
    const offer = first(offers);
    if (!offer) return null;
    const rows = await db.request('bookings', { query: { id: `eq.${offer.booking_id}`, tenant_id: `eq.${t.id}`, select: '*', limit: 1 } });
    const booking = first(rows);
    return booking ? mapBooking(booking, offer) : null;
  }

  async function publicState() {
    const t = await tenant();
    const [drivers, bookingRows, offerRows, events] = await Promise.all([
      driverRows(),
      db.request('bookings', { query: { tenant_id: `eq.${t.id}`, select: '*', order: 'created_at.desc', limit: 150 } }),
      db.request('dispatch_offers', { query: { tenant_id: `eq.${t.id}`, response: 'is.null', select: 'booking_id,driver_id,expires_at,offered_at', order: 'offered_at.desc' } }),
      db.request('dispatch_events', { query: { tenant_id: `eq.${t.id}`, select: 'id,event_type,payload,created_at', order: 'created_at.desc', limit: 40 } })
    ]);
    const offerByBooking = new Map();
    for (const offer of offerRows) if (!offerByBooking.has(offer.booking_id)) offerByBooking.set(offer.booking_id, offer);
    return {
      mode: 'supabase',
      tenant: { id: t.id, name: t.name, slug: t.slug, brandConfig: t.brand_config },
      nightMode: t.night_mode,
      drivers,
      bookings: bookingRows.map((b) => mapBooking(b, offerByBooking.get(b.id))),
      events: events.map((e) => ({ id: e.id, type: e.event_type, at: e.created_at, payload: e.payload }))
    };
  }

  async function setNightMode(enabled) {
    const t = await tenant();
    await db.request('tenants', { method: 'PATCH', query: { id: `eq.${t.id}` }, body: { night_mode: Boolean(enabled) }, prefer: 'return=minimal' });
    t.night_mode = Boolean(enabled); await emit('night_mode.changed', { enabled: t.night_mode }); return t.night_mode;
  }

  async function setDriverStatus(driverId, status) {
    if (!Object.values(DRIVER_STATUS).includes(status)) throw new Error('Invalid driver status.');
    const t = await tenant();
    const driver = first(await db.rpc('set_driver_status_with_shift', {
      p_tenant_id: t.id,
      p_driver_id: driverId,
      p_status: status
    }));
    if (!driver) throw new Error('Driver not found.');
    await emit('driver.status', { driverId, status });
    return driver;
  }

  async function updateDriverLocation(driverId, location) {
    const t = await tenant();
    const payload = { driver_id: driverId, tenant_id: t.id, latitude: Number(location.lat), longitude: Number(location.lng), accuracy_m: Number(location.accuracyM ?? 0), captured_at: new Date().toISOString() };
    if (!Number.isFinite(payload.latitude) || !Number.isFinite(payload.longitude)) throw new Error('Valid latitude and longitude are required.');
    await db.request('driver_locations', { method: 'POST', query: { on_conflict: 'driver_id' }, body: payload, prefer: 'resolution=merge-duplicates,return=minimal' });
    await db.request('drivers', { method: 'PATCH', query: { id: `eq.${driverId}`, tenant_id: `eq.${t.id}` }, body: { last_seen_at: new Date().toISOString() }, prefer: 'return=minimal' });
    notify({ id: null, type: 'driver.location', at: new Date().toISOString(), payload: { driverId, ...payload } });
    return { lat: payload.latitude, lng: payload.longitude, accuracyM: payload.accuracy_m, capturedAt: payload.captured_at };
  }

  async function attemptedDriverIds(bookingId) {
    const rows = await db.request('dispatch_offers', { query: { booking_id: `eq.${bookingId}`, select: 'driver_id' } });
    return rows.map((x) => x.driver_id);
  }

  async function offerNextDriver(booking) {
    const t = await tenant();
    const next = chooseNextDriver({ drivers: await driverRows(), booking, attemptedDriverIds: await attemptedDriverIds(booking.id) });
    if (!next) {
      await db.request('bookings', { method: 'PATCH', query: { id: `eq.${booking.id}`, tenant_id: `eq.${t.id}` }, body: { status: BOOKING_STATUS.UNFULFILLED }, prefer: 'return=minimal' });
      await emit('booking.unfulfilled', { bookingId: booking.id });
      return bookingById(booking.id);
    }
    const expiresAt = new Date(Date.now() + Number(t.offer_timeout_seconds ?? 20) * 1000).toISOString();
    await db.rpc('create_dispatch_offer', { p_tenant_id: t.id, p_booking_id: booking.id, p_driver_id: next.id, p_score: next.dispatchScore, p_distance_km: next.distanceKm, p_expires_at: expiresAt });
    await emit('booking.offered', { bookingId: booking.id, driverId: next.id, distanceKm: next.distanceKm, score: next.dispatchScore });
    return bookingById(booking.id);
  }

  async function respondToOffer({ bookingId, driverId, accept }) {
    const t = await tenant();
    await db.rpc('resolve_dispatch_offer', { p_tenant_id: t.id, p_booking_id: bookingId, p_driver_id: driverId, p_accept: Boolean(accept) });
    await emit(accept ? 'booking.assigned' : 'booking.declined', { bookingId, driverId });
    if (!accept) return offerNextDriver(await bookingById(bookingId));
    return bookingById(bookingId);
  }

  async function arriveTrip({ bookingId, driverId }) {
    const t = await tenant();
    await db.rpc('arrive_driver_trip', { p_tenant_id: t.id, p_booking_id: bookingId, p_driver_id: driverId });
    await emit('booking.arrived', { bookingId, driverId });
    return bookingById(bookingId);
  }

  async function startTrip({ bookingId, driverId }) {
    const t = await tenant();
    await db.rpc('start_driver_trip', { p_tenant_id: t.id, p_booking_id: bookingId, p_driver_id: driverId });
    await emit('booking.started', { bookingId, driverId });
    return bookingById(bookingId);
  }

  async function completeTrip({ bookingId, driverId }) {
    const t = await tenant();
    await db.rpc('complete_driver_trip', { p_tenant_id: t.id, p_booking_id: bookingId, p_driver_id: driverId });
    await emit('booking.completed', { bookingId, driverId });
    return bookingById(bookingId);
  }

  async function expireOffers(now = Date.now()) {
    const t = await tenant();
    const expired = await db.request('dispatch_offers', { query: { tenant_id: `eq.${t.id}`, response: 'is.null', expires_at: `lt.${new Date(now).toISOString()}`, select: 'id,booking_id,driver_id' } });
    for (const offer of expired) {
      const result = await db.rpc('expire_dispatch_offer', { p_tenant_id: t.id, p_offer_id: offer.id });
      if (!result?.expired) continue;
      await emit('booking.offer_expired', { bookingId: offer.booking_id, driverId: offer.driver_id });
      await offerNextDriver(await bookingById(offer.booking_id));
    }
  }

  async function sessionContext(accessToken) {
    const user = await db.getUser(accessToken);
    const t = await tenant();
    const membership = first(await db.request('memberships', { query: { tenant_id: `eq.${t.id}`, user_id: `eq.${user.id}`, select: 'tenant_id,user_id,role', limit: 1 } }));
    if (!membership) throw new Error('This account is not assigned to this taxi base.');
    const driver = first(await db.request('drivers', { query: { tenant_id: `eq.${t.id}`, auth_user_id: `eq.${user.id}`, select: 'id,display_name,vehicle_plate,status', limit: 1 } }));
    return { user: { id: user.id, email: user.email }, membership, driver: driver || null };
  }

  async function login(email, password) {
    const session = await db.login(email, password);
    const context = await sessionContext(session.access_token);
    return { accessToken: session.access_token, refreshToken: session.refresh_token, expiresIn: session.expires_in, ...context };
  }

  async function refresh(refreshToken) {
    const session = await db.refresh(refreshToken);
    const context = await sessionContext(session.access_token);
    return { accessToken: session.access_token, refreshToken: session.refresh_token, expiresIn: session.expires_in, ...context };
  }

  const operations = createOperationsModule({ db, tenant, driverRows, bookingById, offerNextDriver, emit });

  return {
    mode: 'supabase',
    publicState,
    getBooking: bookingById,
    activeOfferForDriver,
    setNightMode,
    setDriverStatus,
    updateDriverLocation,
    createBooking: operations.createBooking,
    activateScheduledBookings: operations.activateScheduledBookings,
    reserveScheduledBooking: operations.reserveScheduledBooking,
    clearScheduledReservation: operations.clearScheduledReservation,
    driverUpcoming: operations.driverUpcoming,
    assignBooking: operations.assignBooking,
    requeueBooking: operations.requeueBooking,
    cancelBooking: operations.cancelBooking,
    driverHistory: operations.driverHistory,
    driverShiftSummary: operations.driverShiftSummary,
    listCustomers: operations.listCustomers,
    createSupportTicket: operations.createSupportTicket,
    listSupportTickets: operations.listSupportTickets,
    resolveSupportTicket: operations.resolveSupportTicket,
    availableDrivers: operations.availableDrivers,
    respondToOffer,
    arriveTrip,
    startTrip,
    completeTrip,
    expireOffers,
    sessionContext,
    login,
    refresh,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); }
  };
}
