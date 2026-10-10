import {
  isAllowedSupportAttachmentRef,
  readSupportImage,
  signSupportAttachment,
  uploadSupportAttachment
} from './support-attachments.js';
import {
  endOperatorShift,
  operatorShiftStatus,
  startOperatorShift
} from './operator-shifts.js';

const DRIVER_NO_SHOW_WAIT_MS = 3 * 60_000;

export async function handleOperationsRequest({
  req, res, path, url, store, authContext, requireRole, requireDriverAccess, body, json, HttpError
}) {
  const historyMatch = path.match(/^\/api\/drivers\/([^/]+)\/history$/);
  if (req.method === 'GET' && historyMatch) {
    const context = await authContext(req);
    requireDriverAccess(context, historyMatch[1]);
    const limit = Number(url.searchParams.get('limit') || 30);
    json(res, 200, { trips: await store.driverHistory(historyMatch[1], limit) });
    return true;
  }

  const upcomingMatch = path.match(/^\/api\/drivers\/([^/]+)\/upcoming$/);
  if (req.method === 'GET' && upcomingMatch) {
    const context = await authContext(req);
    requireDriverAccess(context, upcomingMatch[1]);
    const limit = Number(url.searchParams.get('limit') || 20);
    json(res, 200, { bookings: await store.driverUpcoming(upcomingMatch[1], limit) });
    return true;
  }

  const shiftMatch = path.match(/^\/api\/drivers\/([^/]+)\/shift$/);
  if (req.method === 'GET' && shiftMatch) {
    const context = await authContext(req);
    requireDriverAccess(context, shiftMatch[1]);
    json(res, 200, { shift: await store.driverShiftSummary(shiftMatch[1]) });
    return true;
  }

  if (req.method === 'GET' && path === '/api/drivers/available') {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    json(res, 200, { drivers: await store.availableDrivers() });
    return true;
  }

  if (req.method === 'GET' && path === '/api/customers') {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    json(res, 200, { customers: await store.listCustomers(Number(url.searchParams.get('limit') || 100)) });
    return true;
  }

  if (req.method === 'GET' && path === '/api/operator-shift') {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    json(res, 200, await operatorShiftStatus({
      tenantId: context.membership.tenant_id,
      userId: context.user.id,
      handoverLimit: Number(url.searchParams.get('limit') || 8)
    }));
    return true;
  }

  if (req.method === 'POST' && path === '/api/operator-shift/start') {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    json(res, 200, { shift: await startOperatorShift({
      tenantId: context.membership.tenant_id,
      userId: context.user.id,
      role: context.membership.role
    }) });
    return true;
  }

  if (req.method === 'POST' && path === '/api/operator-shift/end') {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    const input = await body(req);
    const state = await store.publicState();
    const openSupport = await store.listSupportTickets({ limit: 250 });
    const snapshot = {
      availableDrivers: state.drivers.filter((driver) => driver.status === 'available').length,
      busyDrivers: state.drivers.filter((driver) => driver.status === 'busy').length,
      waitingBookings: state.bookings.filter((booking) => ['pending','offering','unfulfilled'].includes(booking.status)).length,
      activeTrips: state.bookings.filter((booking) => ['assigned','arrived','in_progress'].includes(booking.status)).length,
      scheduledBookings: state.bookings.filter((booking) => booking.status === 'scheduled').length,
      openSupport: openSupport.filter((ticket) => ticket.status !== 'resolved').length,
      safetyAlerts: openSupport.filter((ticket) => ticket.category === 'safety' && ticket.status !== 'resolved').length
    };
    json(res, 200, { shift: await endOperatorShift({
      tenantId: context.membership.tenant_id,
      userId: context.user.id,
      note: input.note,
      snapshot
    }) });
    return true;
  }

  if (req.method === 'POST' && path === '/api/support/attachment') {
    const context = await authContext(req);
    if (!['driver', 'admin', 'dispatcher'].includes(context.membership?.role)) throw new HttpError(403, 'You do not have permission to upload support screenshots.');
    const driverId = context.membership?.role === 'driver' ? context.driver?.id : null;
    if (context.membership?.role === 'driver' && !driverId) throw new HttpError(403, 'Driver profile is required.');
    const image = await readSupportImage(req);
    const attachmentUrl = await uploadSupportAttachment({
      tenantId: context.membership.tenant_id,
      ownerId: driverId || context.user?.id || 'operator',
      ...image
    });
    json(res, 201, { attachmentUrl });
    return true;
  }

  const attachmentMatch = path.match(/^\/api\/support\/([^/]+)\/attachment$/);
  if (req.method === 'GET' && attachmentMatch) {
    const context = await authContext(req);
    if (!['driver', 'admin', 'dispatcher'].includes(context.membership?.role)) throw new HttpError(403, 'You do not have permission to view support screenshots.');
    const driverId = context.membership?.role === 'driver' ? context.driver?.id : null;
    if (context.membership?.role === 'driver' && !driverId) throw new HttpError(403, 'Driver profile is required.');
    const tickets = await store.listSupportTickets({ driverId: driverId || null, limit: 250 });
    const ticket = tickets.find((item) => item.id === attachmentMatch[1]);
    if (!ticket) throw new HttpError(404, 'Support ticket not found.');
    if (!ticket.attachment_url) throw new HttpError(404, 'This support ticket has no screenshot.');
    const signedUrl = await signSupportAttachment(ticket.attachment_url, 300);
    json(res, 200, { url: signedUrl, expiresIn: 300 });
    return true;
  }

  if (req.method === 'GET' && path === '/api/support') {
    const context = await authContext(req);
    const driverId = context.membership?.role === 'driver' ? context.driver?.id : url.searchParams.get('driverId');
    if (context.membership?.role === 'driver' && !driverId) throw new HttpError(403, 'Driver profile is required.');
    if (!['driver', 'admin', 'dispatcher'].includes(context.membership?.role)) throw new HttpError(403, 'You do not have permission for support tickets.');
    json(res, 200, { tickets: await store.listSupportTickets({ driverId: driverId || null, limit: Number(url.searchParams.get('limit') || 100) }) });
    return true;
  }

  if (req.method === 'POST' && path === '/api/support') {
    const context = await authContext(req);
    if (!['driver', 'admin', 'dispatcher'].includes(context.membership?.role)) throw new HttpError(403, 'You do not have permission for support tickets.');
    const input = await body(req);
    const driverId = context.membership?.role === 'driver' ? context.driver?.id : (input.driverId || null);
    if (context.membership?.role === 'driver' && !driverId) throw new HttpError(403, 'Driver profile is required.');
    const attachmentUrl = input.attachmentUrl || null;
    const ownerId = context.membership?.role === 'driver' ? driverId : null;
    if (attachmentUrl && !isAllowedSupportAttachmentRef(attachmentUrl, context.membership.tenant_id, ownerId)) {
      throw new HttpError(400, 'Invalid screenshot reference for this account.');
    }
    json(res, 201, await store.createSupportTicket({
      userId: context.user?.id || null,
      driverId,
      bookingId: input.bookingId || null,
      category: input.category,
      priority: input.priority,
      subject: input.subject,
      description: input.description,
      attachmentUrl
    }));
    return true;
  }

  const resolveTicketMatch = path.match(/^\/api\/support\/([^/]+)\/resolve$/);
  if (req.method === 'POST' && resolveTicketMatch) {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    const input = await body(req);
    json(res, 200, await store.resolveSupportTicket({ ticketId: resolveTicketMatch[1], resolutionNote: input.resolutionNote }));
    return true;
  }

  const reserveMatch = path.match(/^\/api\/bookings\/([^/]+)\/reserve$/);
  if (req.method === 'POST' && reserveMatch) {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    const input = await body(req);
    if (!input.driverId) throw new HttpError(400, 'Driver is required.');
    json(res, 200, await store.reserveScheduledBooking({ bookingId: reserveMatch[1], driverId: String(input.driverId) }));
    return true;
  }

  const clearReservationMatch = path.match(/^\/api\/bookings\/([^/]+)\/clear-reservation$/);
  if (req.method === 'POST' && clearReservationMatch) {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    json(res, 200, await store.clearScheduledReservation({ bookingId: clearReservationMatch[1] }));
    return true;
  }

  const assignMatch = path.match(/^\/api\/bookings\/([^/]+)\/assign$/);
  if (req.method === 'POST' && assignMatch) {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    const input = await body(req);
    if (!input.driverId) throw new HttpError(400, 'Driver is required.');
    json(res, 200, await store.assignBooking({ bookingId: assignMatch[1], driverId: String(input.driverId) }));
    return true;
  }

  const requeueMatch = path.match(/^\/api\/bookings\/([^/]+)\/requeue$/);
  if (req.method === 'POST' && requeueMatch) {
    const context = await authContext(req);
    requireRole(context, ['admin', 'dispatcher']);
    json(res, 200, await store.requeueBooking({ bookingId: requeueMatch[1] }));
    return true;
  }

  const cancelMatch = path.match(/^\/api\/bookings\/([^/]+)\/(cancel|no-show)$/);
  if (req.method === 'POST' && cancelMatch) {
    const context = await authContext(req);
    const input = await body(req);
    const booking = await store.getBooking(cancelMatch[1]);
    const noShow = cancelMatch[2] === 'no-show';

    if (context.membership?.role === 'driver') {
      if (!context.driver?.id || booking.assignedDriverId !== context.driver.id) throw new HttpError(403, 'This booking is not assigned to you.');
      if (booking.status === 'in_progress') throw new HttpError(409, 'Contact dispatch to end an active trip.');
      if (noShow) {
        if (booking.status !== 'arrived' || !booking.arrivedAt) throw new HttpError(409, 'Mark arrived before reporting a no-show.');
        if (Date.now() - new Date(booking.arrivedAt).getTime() < DRIVER_NO_SHOW_WAIT_MS) throw new HttpError(409, 'Wait at least 3 minutes after arrival before reporting a no-show.');
      }
    } else {
      requireRole(context, ['admin', 'dispatcher']);
    }

    const reason = String(input.reason || '').trim();
    if (!reason) throw new HttpError(400, 'A reason is required.');
    json(res, 200, await store.cancelBooking({
      bookingId: cancelMatch[1],
      reason,
      code: input.code || (noShow ? 'passenger_no_show' : 'other'),
      actorRole: context.membership?.role || 'unknown',
      noShow
    }));
    return true;
  }

  return false;
}
