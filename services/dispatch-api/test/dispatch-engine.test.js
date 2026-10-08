import test from 'node:test';
import assert from 'node:assert/strict';
import { DRIVER_STATUS, chooseNextDriver, haversineKm, parseStructuredTaxiRequest, rankDrivers } from '../src/dispatch-engine.js';

test('haversine returns near zero for same point', () => {
  assert.equal(haversineKm({ lat: 6.8, lng: -58.1 }, { lat: 6.8, lng: -58.1 }), 0);
});

test('unavailable and busy drivers are not ranked', () => {
  const drivers = [
    { id: 'a', status: DRIVER_STATUS.BUSY, location: { lat: 6.81, lng: -58.15 } },
    { id: 'b', status: DRIVER_STATUS.AVAILABLE, location: { lat: 6.82, lng: -58.15 }, queueRank: 1, availableSince: Date.now() - 10_000 }
  ];
  const ranked = rankDrivers({ drivers, pickup: { lat: 6.819, lng: -58.15 } });
  assert.deepEqual(ranked.map((d) => d.id), ['b']);
});

test('next driver skips attempted drivers', () => {
  const drivers = [
    { id: 'a', status: DRIVER_STATUS.AVAILABLE, location: { lat: 6.81, lng: -58.15 }, queueRank: 1 },
    { id: 'b', status: DRIVER_STATUS.AVAILABLE, location: { lat: 6.82, lng: -58.15 }, queueRank: 2 }
  ];
  const booking = { pickup: { lat: 6.811, lng: -58.15 } };
  const chosen = chooseNextDriver({ drivers, booking, attemptedDriverIds: ['a'] });
  assert.equal(chosen.id, 'b');
});

test('structured request requires pickup and destination', () => {
  assert.throws(() => parseStructuredTaxiRequest({ destination: { label: 'Diamond' } }), /pickup/i);
  assert.throws(() => parseStructuredTaxiRequest({ pickup: { lat: 6.8, lng: -58.1 } }), /destination/i);
});

test('structured request clamps passenger count', () => {
  const result = parseStructuredTaxiRequest({
    passengerName: 'Oscar',
    passengers: 99,
    pickup: { label: 'Albertown', lat: 6.8208, lng: -58.1551 },
    destination: { label: 'Diamond' }
  });
  assert.equal(result.passengers, 8);
});
