# SnapNest Dispatch

**A reusable dispatch operating system for traditional taxi bases.**

SnapNest Dispatch modernizes taxi bases that still depend on noisy radios, manual call-taking, paper queues, and a human dispatcher being physically present. Taxi companies keep their own brand and customers while SnapNest provides the operating layer underneath.

## v0.2 capabilities

- WhatsApp-first booking adapter boundary; customer app is not required
- Dispatcher control center with authenticated base access
- Driver assignment based on availability, proximity, queue fairness, and recent declines
- Accept/decline workflow with automatic fallback to the next suitable driver
- Persistent Supabase/PostgreSQL storage for drivers, GPS locations, bookings, offers, events, and communications
- Multi-tenant membership model with `admin`, `dispatcher`, and `driver` roles
- Row Level Security on every exposed public table
- Atomic database RPCs for offer, accept/decline, and expiration transitions
- Native Android driver app with authenticated session, on-duty GPS sharing, and floating PTT overlay foundation
- Demo/in-memory fallback so the project still runs without cloud credentials

## Run locally in demo mode

Requires Node.js 22+.

```bash
npm test
npm run dev
```

Open:

- Dispatcher: `http://localhost:8787/`
- Customer booking simulator: `http://localhost:8787/customer.html`
- Driver console: `http://localhost:8787/driver.html`

Demo mode needs no Supabase credentials and ships with sample cars around Georgetown.

## Persistent Supabase mode

Copy `.env.example` into your deployment environment and provide:

```text
SUPABASE_URL=
SUPABASE_PUBLISHABLE_KEY=
SUPABASE_SECRET_KEY=
SUPABASE_TENANT_SLUG=demo-base
DISPATCH_REQUIRE_AUTH=true
```

Use **publishable** keys in clients and keep the **secret** key server-side only. The server deliberately does not expose the secret key to the web dashboard or Android app.

Apply the production migration:

`database/migrations/0001_dispatch_v0_2.sql`

Optional demo data is in:

`database/seed.sql`

After creating Supabase Auth users, add each user to `memberships`; drivers must also have their Auth user ID linked through `drivers.auth_user_id`.

## Project layout

```text
apps/
  control-center/      Dispatcher, customer and browser driver surfaces
  driver-android/      Native Android driver app, GPS service, and PTT overlay
services/
  dispatch-api/        Auth, dispatch engine, API, demo store, Supabase store
database/
  migrations/          Production database migrations
  seed.sql             Optional Georgetown demo seed
docs/
  ARCHITECTURE.md      Product architecture
  MVP.md               MVP scope and rollout
```

## Production field-test deployment

The Android driver build bakes in `https://snapnest-dispatch.onrender.com`; drivers do not enter or change an API URL. Override it only for an intentional non-production build with Gradle property `DISPATCH_API_URL`.

Live PTT is enabled only when Render has all three server-side LiveKit values: `LIVEKIT_URL`, `LIVEKIT_API_KEY`, and `LIVEKIT_API_SECRET`. No LiveKit secret or Supabase secret is compiled into the web or Android clients.

Production WhatsApp and AI phone-call adapters remain outside this field-test release. The dispatcher control center, Supabase auth/storage, driver GPS, offer acceptance, trip lifecycle, and LiveKit PTT path are included.

Built by **SnapNest Digital Solutions**.
