# SnapNest Dispatch PTT

## Pilot architecture

SnapNest Dispatch uses a provider adapter with LiveKit as the first real-time audio provider.

- One LiveKit room per taxi-base tenant.
- Room and participant identities are opaque internal UUID-derived values. Do not place customer names, phone numbers, driver plates, or emails in LiveKit room/participant identifiers.
- The API mints short-lived participant tokens after normal SnapNest authentication.
- Drivers and dispatchers join subscribed to the same room with microphones muted.
- Press/hold requests the SnapNest PTT floor lease.
- Only after the floor is granted does the client unmute its microphone.
- Release immediately mutes the microphone and releases the floor.
- Floor leases expire automatically if a client disappears while transmitting.

## Environment

Server only:

- `LIVEKIT_URL`
- `LIVEKIT_API_KEY`
- `LIVEKIT_API_SECRET`

Never expose the LiveKit API secret to a browser or Android client. Clients receive participant tokens from `/api/ptt/token`.

## API

- `GET /api/ptt/token`
- `POST /api/ptt/floor/acquire`
- `POST /api/ptt/floor/heartbeat`
- `POST /api/ptt/floor/release`

## Pilot limitations

The current floor manager is in-process and assumes one dispatch API instance. Before horizontal scaling, move the floor lease to shared infrastructure (Postgres advisory/state table, Redis, or equivalent) so every API instance sees the same speaker lock.

The PTT layer is for taxi-base operations and is not an emergency communications service.
