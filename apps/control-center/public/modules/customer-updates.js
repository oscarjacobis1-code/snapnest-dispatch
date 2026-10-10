// These are operator-reviewed WhatsApp drafts. Opening a link never records a sent message.
export function customerUpdate(booking, drivers = [], baseName = 'Taxi base') {
  const phone = String(booking?.passengerPhone || '').replace(/\D/g, '');
  if (!/^\d{8,15}$/.test(phone)) return null;
  const reference = String(booking.id || '').slice(0, 8).toUpperCase();
  const base = String(baseName || 'Taxi base').slice(0, 80);
  const driver = drivers.find((item) => item.id === booking.assignedDriverId);
  const pickup = String(booking.pickup?.label || 'your pickup point').slice(0, 160);
  const vehicle = driver?.vehicle ? ` Vehicle: ${String(driver.vehicle).slice(0, 60)}.` : '';
  const driverCopy = driver?.name ? ` Driver: ${String(driver.name).slice(0, 80)}.` : '';
  const messages = {
    scheduled: `Your taxi request #${reference} with ${base} is scheduled for ${booking.scheduledFor ? new Date(booking.scheduledFor).toLocaleString('en-GY', { timeZone: 'America/Guyana', dateStyle: 'medium', timeStyle: 'short' }) : 'the agreed time'}. Pickup: ${pickup}. Reply here if anything changes.`,
    pending: `We received your taxi request #${reference} with ${base}. We are finding a driver. Pickup: ${pickup}. We will confirm when a driver accepts.`,
    offering: `We received your taxi request #${reference} with ${base}. We are finding a driver. Pickup: ${pickup}. We will confirm when a driver accepts.`,
    unfulfilled: `Update for taxi request #${reference}: ${base} has not found an available driver yet. Please contact the base if you still need a ride.`,
    assigned: `Your taxi #${reference} is confirmed and heading to ${pickup}.${driverCopy}${vehicle} Please look out for the vehicle.`,
    arrived: `Your taxi #${reference} has arrived at ${pickup}.${vehicle} Please come to the pickup point or reply if you cannot find it.`,
    in_progress: `Your trip #${reference} with ${base} has started.`,
    completed: `Your trip #${reference} with ${base} is complete. Thank you for riding with us.`,
    cancelled: `Your taxi request #${reference} with ${base} has been cancelled. Reply here if you need help.`,
    no_show: `Your taxi request #${reference} with ${base} was closed after the driver could not meet you at pickup. Reply here if you need help.`
  };
  const message = messages[booking.status];
  return message ? { phone, message, url: `https://wa.me/${phone}?text=${encodeURIComponent(message)}` } : null;
}
