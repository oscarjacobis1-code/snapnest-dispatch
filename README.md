# SnapNest Dispatch

**A reusable dispatch operating system for traditional taxi bases.**

SnapNest Dispatch is designed for taxi companies that still depend on radios, manual call-taking, paper queues, and a human dispatcher being physically present. The platform keeps the base's brand and customers while modernizing the machinery underneath.

## MVP goals

- WhatsApp-first customer booking (simulated in v0.1; Meta Cloud API adapter comes next)
- Dispatcher control center with live drivers, jobs, and night-mode automation
- Driver assignment based on availability + proximity + queue fairness
- Accept/decline workflow with automatic fallback to the next suitable driver
- Android-native driver app foundation
- Persistent floating push-to-talk control on Android
- Multi-tenant data model so one codebase can power many taxi bases
- No customer app requirement

## Run the prototype

Requires Node.js 22+.

```bash
npm test
npm run dev
```

Then open:

- Dispatcher: http://localhost:8787/
- Customer booking simulator: http://localhost:8787/customer.html
- Driver simulator: http://localhost:8787/driver.html?driver=d1

The prototype ships with demo drivers around Georgetown so the dispatch engine can be tested immediately.

## Project layout

```text
apps/
  control-center/      Dispatcher, customer and driver browser prototypes
  driver-android/      Native Android driver app + overlay service foundation
services/
  dispatch-api/        Dispatch engine, state, API and realtime SSE feed
database/
  schema.sql           Production-oriented multi-tenant PostgreSQL schema
docs/
  ARCHITECTURE.md      Target production architecture
  MVP.md               Scope and staged rollout
```

## Product principle

The customer should not have to download another taxi app. The customer interacts through WhatsApp, phone, or a lightweight web flow. Drivers and dispatchers get purpose-built software because they use the system every day.

## Status

v0.1 establishes the dispatch loop and Android overlay foundation. It intentionally does **not** fake production WhatsApp, telephony, payments, GPS streaming, or voice AI integrations. Those adapters are next after the core dispatch behavior is verified.

Built by **SnapNest Digital Solutions**.
