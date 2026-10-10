import test from 'node:test';
import assert from 'node:assert/strict';
import { createRuntimeStore } from '../src/runtime-store.js';

function futureIso(minutes) {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

test('scheduled booking can be reserved and appears in driver upcoming jobs', async () => {
  const { store } = createRuntimeStore({});
  const booking = await store.createBooking({
    source: 'dispatcher',
    passengerName: 'Reservation Test',
    passengerPhone: '5920000000',
    passengers: 1,
    notes: '',
    pickup: { label: 'Pickup', lat: 6.812, lng: -58.155 },
    destination: { label: 'Destination', lat: 6.82, lng: -58.16 },
    scheduledFor: futureIso(45)
  });

  assert.equal(booking.status, 'scheduled');
  const reserved = await store.reserveScheduledBooking({ bookingId: booking.id, driverId: 'd1' });
  assert.equal(reserved.reservedDriverId, 'd1');

  const upcoming = await store.driverUpcoming('d1', 20);
  assert.ok(upcoming.some((item) => item.id === booking.id));

  const cleared = await store.clearScheduledReservation({ bookingId: booking.id });
  assert.equal(cleared.reservedDriverId, null);
});

test('reserved driver is assigned when a scheduled booking enters the lead window', async () => {
  const { store } = createRuntimeStore({});
  const booking = await store.createBooking({
    source: 'dispatcher',
    passengerName: 'Lead Window Test',
    passengerPhone: '5920000001',
    passengers: 1,
    notes: '',
    pickup: { label: 'Pickup', lat: 6.813, lng: -58.154 },
    destination: { label: 'Destination', lat: 6.821, lng: -58.161 },
    scheduledFor: futureIso(10)
  });

  await store.reserveScheduledBooking({ bookingId: booking.id, driverId: 'd2' });
  const activated = await store.activateScheduledBookings(Date.now(), 15);
  const assigned = activated.find((item) => item.id === booking.id);

  assert.ok(assigned);
  assert.equal(assigned.status, 'assigned');
  assert.equal(assigned.assignedDriverId, 'd2');
});

test('invalid or past scheduled times never create an immediate booking', async () => {
  const { store } = createRuntimeStore({});
  const input = {
    passengerName: 'Schedule Validation', passengers: 1,
    pickup: { label: 'Pickup', lat: 6.812, lng: -58.155 },
    destination: { label: 'Destination', lat: 6.82, lng: -58.16 }
  };
  await assert.rejects(store.createBooking({ ...input, scheduledFor: 'bad-date' }), /invalid/);
  await assert.rejects(store.createBooking({ ...input, scheduledFor: new Date(Date.now() - 60_000).toISOString() }), /future/);
  const state = await store.publicState();
  assert.ok(!state.bookings.some((booking) => booking.passengerName === input.passengerName));
});

test('rescheduling preserves the reserved driver and moves the activation window', async () => {
  const { store } = createRuntimeStore({});
  const booking = await store.createBooking({
    passengerName: 'Reschedule Test', passengers: 1,
    pickup: { label: 'Pickup', lat: 6.812, lng: -58.155 },
    destination: { label: 'Destination', lat: 6.82, lng: -58.16 },
    scheduledFor: futureIso(10)
  });
  await store.reserveScheduledBooking({ bookingId: booking.id, driverId: 'd1' });
  const changed = await store.rescheduleBooking({ bookingId: booking.id, scheduledFor: futureIso(60) });
  assert.equal(changed.reservedDriverId, 'd1');
  assert.ok((await store.driverUpcoming('d1')).some((item) => item.id === booking.id && item.scheduledFor === changed.scheduledFor));
  assert.ok(!(await store.activateScheduledBookings(Date.now(), 15)).some((item) => item.id === booking.id));
  assert.equal(changed.status, 'scheduled');
  await assert.rejects(store.rescheduleBooking({ bookingId: booking.id, scheduledFor: 'nonsense' }), /invalid/);
  await assert.rejects(store.rescheduleBooking({ bookingId: booking.id, scheduledFor: futureIso(-5) }), /future/);
  assert.equal((await store.getBooking(booking.id)).scheduledFor, changed.scheduledFor);
  await store.cancelBooking({ bookingId: booking.id, reason: 'test' });
  await assert.rejects(store.rescheduleBooking({ bookingId: booking.id, scheduledFor: futureIso(90) }), /Only scheduled/);
});
