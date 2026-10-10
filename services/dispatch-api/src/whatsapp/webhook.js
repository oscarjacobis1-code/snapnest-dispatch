import { createHmac, timingSafeEqual } from 'node:crypto';

export function secureMatch(expected, received) {
  if (!expected || !received) return false;
  const a = Buffer.from(String(expected));
  const b = Buffer.from(String(received));
  return a.length === b.length && timingSafeEqual(a, b);
}

export function verifyMetaSignature(raw, signature, appSecret) {
  if (!appSecret || !/^sha256=[a-f0-9]{64}$/i.test(String(signature || ''))) return false;
  const expected = createHmac('sha256', appSecret).update(raw).digest('hex');
  return secureMatch(`sha256=${expected}`, signature);
}

export function incomingMessages(event, phoneNumberId) {
  if (event?.object !== 'whatsapp_business_account') return [];
  const messages = [];
  for (const entry of event.entry || []) for (const change of entry.changes || []) {
    const value = change.value || {};
    if (change.field !== 'messages' || String(value.metadata?.phone_number_id || '') !== phoneNumberId) continue;
    for (const message of value.messages || []) {
      const sender = String(message.from || '');
      const externalId = String(message.id || '');
      if (!/^\d{8,15}$/.test(sender) || !externalId || externalId.length > 160) continue;
      const profile = (value.contacts || []).find((contact) => contact.wa_id === sender)?.profile?.name;
      const location = message.type === 'location' ? {
        lat: Number(message.location?.latitude), lng: Number(message.location?.longitude),
        label: String(message.location?.name || message.location?.address || 'Shared location').slice(0, 160)
      } : null;
      const validLocation = location && Number.isFinite(location.lat) && Math.abs(location.lat) <= 90 && Number.isFinite(location.lng) && Math.abs(location.lng) <= 180;
      const text = message.type === 'text' ? String(message.text?.body || '').slice(0, 1500) : null;
      if (!validLocation && !text) continue;
      messages.push({ externalId, sender, name: String(profile || 'WhatsApp customer').slice(0, 80), type: validLocation ? 'location' : 'text', text, location: validLocation ? location : null });
    }
  }
  return messages.slice(0, 30);
}
