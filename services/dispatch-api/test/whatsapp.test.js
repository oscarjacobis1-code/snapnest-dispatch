import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { incomingMessages, verifyMetaSignature } from '../src/whatsapp/webhook.js';
import { buildWhatsAppUrl } from '../../../apps/control-center/public/customer-whatsapp.js';
import { parseRequestText } from '../../../apps/control-center/public/modules/whatsapp-format.js';
import { customerUpdate } from '../../../apps/control-center/public/modules/customer-updates.js';
import { bookingCard } from '../../../apps/control-center/public/modules/bookings-ui.js';

test('Meta signature is checked against the raw body', () => {
  const raw = Buffer.from('{"object":"whatsapp_business_account"}');
  const signature = `sha256=${createHmac('sha256', 'test-secret').update(raw).digest('hex')}`;
  assert.equal(verifyMetaSignature(raw, signature, 'test-secret'), true);
  assert.equal(verifyMetaSignature(Buffer.from('{}'), signature, 'test-secret'), false);
  assert.equal(verifyMetaSignature(raw, signature, ''), false);
  assert.equal(verifyMetaSignature(raw, 'sha256=nope', 'test-secret'), false);
});

test('WhatsApp parser accepts only messages for the configured number', () => {
  const event = { object: 'whatsapp_business_account', entry: [{ changes: [{ field: 'messages', value: {
    metadata: { phone_number_id: 'number-1' }, contacts: [{ wa_id: '5926000000', profile: { name: '<script>Customer</script>' } }],
    messages: [{ id: 'wamid.1', from: '5926000000', type: 'text', text: { body: 'Taxi request' } },
      { id: 'wamid.2', from: '5926000000', type: 'location', location: { latitude: 6.812, longitude: -58.16 } }]
  } }] }] };
  assert.equal(incomingMessages(event, 'other').length, 0);
  const messages = incomingMessages(event, 'number-1');
  assert.equal(messages.length, 2);
  assert.equal(messages[0].sender, '5926000000');
  assert.deepEqual(messages[1].location, { lat: 6.812, lng: -58.16, label: 'Shared location' });
});

test('customer request opens the configured WhatsApp contact and stays a draft', () => {
  const url = new URL(buildWhatsAppUrl('5926000000', { name: 'Asha', pickup: 'Stabroek', destination: 'Diamond', passengers: 2 }));
  assert.equal(url.origin, 'https://wa.me');
  assert.equal(url.pathname, '/5926000000');
  assert.match(url.searchParams.get('text'), /Pickup: Stabroek/);
  assert.equal(parseRequestText(url.searchParams.get('text')).destination, 'Diamond');
  assert.throws(() => buildWhatsAppUrl('javascript:alert(1)', { name: 'Asha', pickup: 'A', destination: 'B', passengers: 1 }), /not configured/);
});

test('operator update uses the real booking state and assigned vehicle without inventing an ETA', () => {
  const booking = { id: 'abc12345-6789', passengerPhone: '+592 600 0000', status: 'assigned', pickup: { label: 'Stabroek Market' }, assignedDriverId: 'd1' };
  const drivers = [{ id: 'd1', name: 'Asha', vehicle: 'HC 1234' }];
  const update = customerUpdate(booking, drivers, 'Demo Base');
  assert.equal(new URL(update.url).pathname, '/5926000000');
  assert.match(update.message, /confirmed/);
  assert.match(update.message, /HC 1234/);
  assert.doesNotMatch(update.message, /min|ETA/i);
  assert.match(bookingCard({ drivers, tenant: { name: 'Demo Base' } }, booking), /data-customer-update/);
  assert.match(customerUpdate({ ...booking, status: 'unfulfilled' }, drivers).message, /has not found an available driver/);
  assert.match(customerUpdate({ ...booking, status: 'cancelled' }, drivers).message, /cancelled/);
  assert.equal(customerUpdate({ ...booking, passengerPhone: 'not-a-number' }, drivers), null);
});
