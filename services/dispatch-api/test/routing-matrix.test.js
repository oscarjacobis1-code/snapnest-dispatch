import test from 'node:test';
import assert from 'node:assert/strict';
import { chooseDriverWithRoadEta } from '../src/routing/mapbox-matrix.js';

const pickup = { lat: 6.812, lng: -58.155 };
const baseDrivers = [
  {
    id: 'near', status: 'available', queueRank: 0, recentDeclines: 0,
    availableSince: Date.now() - 5 * 60_000,
    location: { lat: 6.811, lng: -58.154 }
  },
  {
    id: 'road-fast', status: 'available', queueRank: 0, recentDeclines: 0,
    availableSince: Date.now() - 5 * 60_000,
    location: { lat: 6.82, lng: -58.16 }
  }
];

test('falls back to existing proximity/fairness ranking without a routing token', async () => {
  const result = await chooseDriverWithRoadEta({
    drivers: baseDrivers,
    booking: { pickup },
    accessToken: ''
  });
  assert.equal(result.routed, false);
  assert.equal(result.provider, 'haversine');
  assert.equal(result.driver.id, 'near');
});

test('road ETA can beat straight-line proximity for dispatch selection', async () => {
  let requestedUrl = '';
  const result = await chooseDriverWithRoadEta({
    drivers: baseDrivers,
    booking: { pickup },
    accessToken: 'test-token',
    fetchImpl: async (url) => {
      requestedUrl = String(url);
      return {
        ok: true,
        async json() {
          return {
            code: 'Ok',
            durations: [[600], [180]],
            distances: [[5000], [2400]]
          };
        }
      };
    }
  });

  assert.equal(result.routed, true);
  assert.equal(result.provider, 'mapbox');
  assert.equal(result.driver.id, 'road-fast');
  assert.equal(result.driver.etaSeconds, 180);
  assert.equal(result.driver.roadDistanceKm, 2.4);
  assert.match(requestedUrl, /sources=0%3B1/);
  assert.match(requestedUrl, /destinations=2/);
  assert.match(requestedUrl, /annotations=duration%2Cdistance/);
});

test('attempted drivers are excluded before the Matrix request', async () => {
  let url = '';
  const result = await chooseDriverWithRoadEta({
    drivers: baseDrivers,
    booking: { pickup },
    attemptedDriverIds: ['near'],
    accessToken: 'test-token',
    fetchImpl: async (input) => {
      url = String(input);
      return {
        ok: true,
        async json() { return { code: 'Ok', durations: [[240]], distances: [[1800]] }; }
      };
    }
  });

  assert.equal(result.driver.id, 'road-fast');
  assert.match(url, /sources=0/);
  assert.match(url, /destinations=1/);
});
