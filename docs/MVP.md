# MVP rollout

## v0.1 — dispatch loop (this repository)

- Demo customer booking
- Live dispatcher dashboard
- Driver simulator
- Availability, offer, accept/decline and timeout fallback
- Explainable driver ranking
- Night-mode state
- Android overlay foundation
- Multi-tenant SQL design

## v0.2 — real backend

- Supabase project and RLS
- Authentication for dispatcher/admin/driver
- Realtime driver locations
- Job persistence and audit history
- Tenant branding configuration

## v0.3 — WhatsApp pilot

- Meta Cloud API webhook validation
- Customer initiated booking flow
- Location-message intake
- Text intent extraction
- Booking confirmation buttons
- Status updates to customer
- Human takeover / exception queue

## v0.4 — voice-note intake

- Download inbound WhatsApp audio securely
- Speech transcription
- Guyana location dictionary
- Structured extraction + confidence score
- Confirm pickup and destination before dispatch
- Capture corrections for vocabulary improvement

## v0.5 — driver communications

- Real Android authentication and GPS
- Foreground PTT overlay
- WebRTC/audio transport
- Individual, available-drivers and shift groups
- Notification fallback

## v0.6 — AI night operations

- Automated WhatsApp dispatch after hours
- Phone channel pilot
- Call transcription and confirmation
- Escalation rules for unclear location, no drivers, emergencies and abusive calls
- Revenue recovery reporting

## Pilot success metrics

The pilot should prove business value, not just technical novelty:

- % of inbound requests answered
- % of confirmed bookings assigned
- median time from request to driver acceptance
- requests lost due to no car vs driver rejection vs customer cancellation
- night bookings recovered
- driver acceptance rate
- customer wait estimate accuracy
- human interventions per 100 bookings
