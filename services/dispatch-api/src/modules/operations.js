import { BOOKING_STATUS } from '../dispatch-engine.js';

const first = (value) => Array.isArray(value) ? value[0] : value;

export function createOperationsModule({ db, tenant, driverRows, bookingById, offerNextDriver, emit }) {
  async function upsertCustomer({ name, phone }) {
    const cleanPhone = String(phone || '').trim();
    if (!cleanPhone) return null;
    const t = await tenant();
    const existing = first(await db.request('customer_profiles', {
      query: { tenant_id: `eq.${t.id}`, phone_e164: `eq.${cleanPhone}`, select: '*', limit: 1 }
    }));
    if (existing) {
      const rows = await db.request('customer_profiles', {
        method: 'PATCH',
        query: { id: `eq.${existing.id}`, tenant_id: `eq.${t.id}` },
        body: {
          display_name: String(name || existing.display_name || 'Guest').trim() || 'Guest',
          total_bookings: Number(existing.total_bookings || 0) + 1,
          last_booking_at: new Date().toISOString(),
          updated_at: new Date().toISOString()
        },
        prefer: 'return=representation'
      });
      return first(rows);
    }
    const rows = await db.request('customer_profiles', {
      method: 'POST',
      body: {
        tenant_id: t.id,
        display_name: String(name || 'Guest').trim() || 'Guest',
        phone_e164: cleanPhone,
        total_bookings: 1,
        last_booking_at: new Date().toISOString()
      },
      prefer: 'return=representation'
    });
    return first(rows);
  }

  async function createBooking(input) {
    const t = await tenant();
    const scheduledAt = input.scheduledFor ? new Date(input.scheduledFor) : null;
    const isScheduled = scheduledAt && Number.isFinite(scheduledAt.getTime()) && scheduledAt.getTime() > Date.now() + 60_000;
    const rows = await db.request('bookings', {
      method: 'POST',
      body: {
        tenant_id: t.id,
        source: input.source ?? 'web',
        customer_name: input.passengerName,
        customer_phone_e164: input.passengerPhone || null,
        passengers: input.passengers,
        notes: input.notes || null,
        pickup_label: input.pickup.label,
        pickup_lat: input.pickup.lat,
        pickup_lng: input.pickup.lng,
        destination_label: input.destination.label,
        destination_lat: input.destination.lat,
        destination_lng: input.destination.lng,
        scheduled_for: isScheduled ? scheduledAt.toISOString() : null,
        status: isScheduled ? BOOKING_STATUS.SCHEDULED : BOOKING_STATUS.PENDING
      },
      prefer: 'return=representation'
    });
    const row = first(rows);
    if (!row) throw new Error('Booking could not be created.');
    await upsertCustomer({ name: input.passengerName, phone: input.passengerPhone });
    await emit('booking.created', { bookingId: row.id, source: row.source, scheduledFor: row.scheduled_for || null });
    const booking = await bookingById(row.id);
    return isScheduled ? booking : offerNextDriver(booking);
  }

  async function activateScheduledBookings(now = Date.now(), leadMinutes = 15) {
    const t = await tenant();
    const threshold = new Date(now + leadMinutes * 60_000).toISOString();
    const rows = await db.request('bookings', {
      query: {
        tenant_id: `eq.${t.id}`,
        status: 'eq.scheduled',
        scheduled_for: `lte.${threshold}`,
        select: 'id,reserved_driver_id',
        order: 'scheduled_for.asc',
        limit: 25
      }
    });
    const activated = [];
    for (const row of rows) {
      const updated = await db.request('bookings', {
        method: 'PATCH',
        query: { id: `eq.${row.id}`, tenant_id: `eq.${t.id}`, status: 'eq.scheduled' },
        body: { status: BOOKING_STATUS.PENDING, updated_at: new Date().toISOString() },
        prefer: 'return=representation'
      });
      if (!updated?.length) continue;
      await emit('booking.scheduled_ready', { bookingId: row.id, reservedDriverId: row.reserved_driver_id || null });

      if (row.reserved_driver_id) {
        const reserved = (await driverRows()).find((driver) => driver.id === row.reserved_driver_id);
        if (reserved?.status === 'available') {
          await db.rpc('assign_booking_driver', {
            p_tenant_id: t.id,
            p_booking_id: row.id,
            p_driver_id: row.reserved_driver_id
          });
          await emit('booking.reservation_assigned', { bookingId: row.id, driverId: row.reserved_driver_id });
          activated.push(await bookingById(row.id));
          continue;
        }
        await emit('booking.reservation_unavailable', { bookingId: row.id, driverId: row.reserved_driver_id });
      }

      activated.push(await offerNextDriver(await bookingById(row.id)));
    }
    return activated;
  }

  async function reserveScheduledBooking({ bookingId, driverId }) {
    const t = await tenant();
    await db.rpc('reserve_scheduled_booking', {
      p_tenant_id: t.id,
      p_booking_id: bookingId,
      p_driver_id: driverId
    });
    await emit('booking.reserved', { bookingId, driverId });
    return bookingById(bookingId);
  }

  async function clearScheduledReservation({ bookingId }) {
    const t = await tenant();
    const before = await bookingById(bookingId);
    await db.rpc('clear_scheduled_reservation', {
      p_tenant_id: t.id,
      p_booking_id: bookingId
    });
    await emit('booking.reservation_cleared', { bookingId, driverId: before.reservedDriverId || null });
    return bookingById(bookingId);
  }

  async function driverUpcoming(driverId, limit = 20) {
    const t = await tenant();
    const safeLimit = Math.max(1, Math.min(50, Number(limit) || 20));
    const rows = await db.request('bookings', {
      query: {
        tenant_id: `eq.${t.id}`,
        reserved_driver_id: `eq.${driverId}`,
        status: 'eq.scheduled',
        scheduled_for: `gt.${new Date().toISOString()}`,
        select: '*',
        order: 'scheduled_for.asc',
        limit: safeLimit
      }
    });
    return rows.map((row) => ({
      id: row.id,
      passengerName: row.customer_name || 'Guest',
      passengerPhone: row.customer_phone_e164 || '',
      passengers: Number(row.passengers || 1),
      notes: row.notes || '',
      pickup: { label: row.pickup_label, lat: Number(row.pickup_lat), lng: Number(row.pickup_lng) },
      destination: {
        label: row.destination_label,
        lat: row.destination_lat == null ? null : Number(row.destination_lat),
        lng: row.destination_lng == null ? null : Number(row.destination_lng)
      },
      scheduledFor: row.scheduled_for,
      status: row.status
    }));
  }

  async function assignBooking({ bookingId, driverId }) {
    const t = await tenant();
    await db.rpc('assign_booking_driver', { p_tenant_id: t.id, p_booking_id: bookingId, p_driver_id: driverId });
    await emit('booking.manual_assigned', { bookingId, driverId });
    return bookingById(bookingId);
  }

  async function requeueBooking({ bookingId }) {
    const t = await tenant();
    await db.rpc('requeue_booking', { p_tenant_id: t.id, p_booking_id: bookingId });
    await emit('booking.requeued', { bookingId });
    return offerNextDriver(await bookingById(bookingId));
  }

  async function cancelBooking({ bookingId, reason, code, actorRole, noShow = false }) {
    const t = await tenant();
    await db.rpc('cancel_booking', {
      p_tenant_id: t.id,
      p_booking_id: bookingId,
      p_reason: String(reason || '').slice(0, 300),
      p_code: String(code || '').slice(0, 64),
      p_actor_role: String(actorRole || '').slice(0, 32),
      p_no_show: Boolean(noShow)
    });
    await emit(noShow ? 'booking.no_show' : 'booking.cancelled', { bookingId, reason, code, actorRole });
    return bookingById(bookingId);
  }

  async function driverHistory(driverId, limit = 30) {
    const t = await tenant();
    const safeLimit = Math.max(1, Math.min(100, Number(limit) || 30));
    const rows = await db.request('bookings', {
      query: {
        tenant_id: `eq.${t.id}`,
        assigned_driver_id: `eq.${driverId}`,
        status: 'in.(completed,cancelled,no_show)',
        select: '*',
        order: 'created_at.desc',
        limit: safeLimit
      }
    });
    return rows.map((row) => ({
      id: row.id,
      source: row.source,
      passengerName: row.customer_name || 'Guest',
      pickup: { label: row.pickup_label, lat: Number(row.pickup_lat), lng: Number(row.pickup_lng) },
      destination: { label: row.destination_label, lat: row.destination_lat == null ? null : Number(row.destination_lat), lng: row.destination_lng == null ? null : Number(row.destination_lng) },
      status: row.status,
      createdAt: row.created_at,
      scheduledFor: row.scheduled_for,
      assignedAt: row.assigned_at,
      arrivedAt: row.arrived_at,
      startedAt: row.started_at,
      completedAt: row.completed_at,
      cancelledAt: row.cancelled_at,
      cancellationReason: row.cancellation_reason,
      cancellationCode: row.cancellation_code,
      noShowAt: row.no_show_at
    }));
  }

  async function listCustomers(limit = 100) {
    const t = await tenant();
    return db.request('customer_profiles', {
      query: { tenant_id: `eq.${t.id}`, select: '*', order: 'last_booking_at.desc', limit: Math.max(1, Math.min(250, Number(limit) || 100)) }
    });
  }

  async function createSupportTicket({ userId, driverId, bookingId, category, priority, subject, description, attachmentUrl }) {
    const t = await tenant();
    const cleanSubject = String(subject || '').trim().slice(0, 120);
    const cleanDescription = String(description || '').trim().slice(0, 2000);
    if (!cleanSubject || !cleanDescription) throw new Error('Ticket subject and description are required.');
    const rows = await db.request('support_tickets', {
      method: 'POST',
      body: {
        tenant_id: t.id,
        created_by_user_id: userId || null,
        driver_id: driverId || null,
        booking_id: bookingId || null,
        category: category || 'app',
        priority: priority || 'normal',
        subject: cleanSubject,
        description: cleanDescription,
        attachment_url: attachmentUrl || null
      },
      prefer: 'return=representation'
    });
    const ticket = first(rows);
    await emit('support.created', { ticketId: ticket?.id, driverId: driverId || null, priority: ticket?.priority || priority || 'normal', category: ticket?.category || category || 'app' });
    return ticket;
  }

  async function listSupportTickets({ driverId = null, limit = 100 } = {}) {
    const t = await tenant();
    const query = {
      tenant_id: `eq.${t.id}`,
      select: '*',
      order: 'created_at.desc',
      limit: Math.max(1, Math.min(250, Number(limit) || 100))
    };
    if (driverId) query.driver_id = `eq.${driverId}`;
    return db.request('support_tickets', { query });
  }

  async function resolveSupportTicket({ ticketId, resolutionNote }) {
    const t = await tenant();
    const rows = await db.request('support_tickets', {
      method: 'PATCH',
      query: { id: `eq.${ticketId}`, tenant_id: `eq.${t.id}` },
      body: { status: 'resolved', resolution_note: String(resolutionNote || '').trim().slice(0, 1000) || null, resolved_at: new Date().toISOString(), updated_at: new Date().toISOString() },
      prefer: 'return=representation'
    });
    const ticket = first(rows);
    if (!ticket) throw new Error('Support ticket not found.');
    await emit('support.resolved', { ticketId });
    return ticket;
  }

  async function availableDrivers() {
    return (await driverRows()).filter((driver) => driver.status === 'available');
  }

  return {
    createBooking,
    activateScheduledBookings,
    reserveScheduledBooking,
    clearScheduledReservation,
    driverUpcoming,
    assignBooking,
    requeueBooking,
    cancelBooking,
    driverHistory,
    listCustomers,
    createSupportTicket,
    listSupportTickets,
    resolveSupportTicket,
    availableDrivers
  };
}
