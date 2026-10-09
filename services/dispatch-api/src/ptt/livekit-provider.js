import { AccessToken } from 'livekit-server-sdk';

export function liveKitPttConfig(env = process.env) {
  const url = String(env.LIVEKIT_URL ?? '').trim();
  const apiKey = String(env.LIVEKIT_API_KEY ?? '').trim();
  const apiSecret = String(env.LIVEKIT_API_SECRET ?? '').trim();
  return {
    provider: 'livekit',
    enabled: Boolean(url && apiKey && apiSecret),
    url,
    apiKey,
    apiSecret
  };
}

export async function createPttToken(context, env = process.env) {
  const config = liveKitPttConfig(env);
  if (!config.enabled) return { enabled: false, provider: config.provider };
  const tenantId = String(context.membership?.tenant_id ?? '');
  const userId = String(context.user?.id ?? '');
  const role = String(context.membership?.role ?? '');
  if (!tenantId || !userId || !role) throw new Error('PTT identity is incomplete.');

  // Keep LiveKit room and participant identities opaque. No email, phone, plate or customer data.
  const roomName = `dispatch-${tenantId}`;
  const identity = `${role}-${userId}`;
  const token = new AccessToken(config.apiKey, config.apiSecret, {
    identity,
    ttl: '2h',
    attributes: { role, tenantId }
  });
  token.addGrant({
    roomJoin: true,
    room: roomName,
    canPublish: true,
    canSubscribe: true,
    canPublishData: false
  });

  return {
    enabled: true,
    provider: config.provider,
    serverUrl: config.url,
    roomName,
    participantToken: await token.toJwt(),
    identity,
    role,
    expiresIn: 7200
  };
}
