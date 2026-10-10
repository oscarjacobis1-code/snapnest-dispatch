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
    json(res, 201, await store.createSupportTicket({
      userId: context.user?.id || null,
      driverId,
      bookingId: input.bookingId || null,
      category: input.category,
      priority: input.priority,
      subject: input.subject,
      description: input.description,
      attachmentUrl: input.attachmentUrl || null
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
