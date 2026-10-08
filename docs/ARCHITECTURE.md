# Architecture

## Product boundary

SnapNest Dispatch is infrastructure for taxi bases, not a consumer ride-hailing marketplace in v1. Each base owns its brand, number, drivers and customers. The platform is deliberately multi-tenant so a future cross-base network can be added without rewriting the dispatch core.

## Production services

1. **Customer channels**
   - WhatsApp Cloud API: text, buttons, shared location, voice notes.
   - Phone/voice: later adapter for automated night intake and human fallback.
   - Lightweight web/PWA booking: optional fallback.

2. **Dispatch API**
   - Validates requests and tenant policy.
   - Persists bookings and conversation state.
   - Scores eligible drivers using proximity, availability and queue fairness.
   - Offers work sequentially with a short timeout, then falls back automatically.
   - Emits realtime events to dispatchers and drivers.

3. **Dispatcher control center**
   - Live fleet map.
   - Open/assigned/completed trip queue.
   - Driver state and rejection visibility.
   - Manual override of automated assignment.
   - Individual/group push-to-talk.
   - Night automation toggle and exception queue.

4. **Android driver app**
   - Online/available/busy/off-duty state.
   - Foreground location only while on duty.
   - Incoming trip card and accept/decline.
   - Navigation handoff.
   - Persistent movable PTT overlay when on duty.
   - Notification fallback if Android suppresses the overlay.

5. **Data layer**
   - PostgreSQL/Supabase with strict tenant isolation and RLS.
   - Realtime updates for job and driver state.
   - Storage for voice-note/audio metadata only when operationally needed.
   - Audit log for assignment decisions and manual overrides.

6. **AI layer**
   - Speech-to-text for WhatsApp voice notes and later phone audio.
   - Structured extraction of pickup, destination, passenger count and notes.
   - Confidence/confirmation workflow before a car is dispatched.
   - Guyana place-name vocabulary and corrected-location dictionary.
   - AI is an intake parser, not the source of dispatch truth.

## Dispatch scoring

The MVP score is intentionally explainable:

`score = distance + queue penalty + recent decline penalty - idle time credit`

Lower wins. Production will add configurable service zones, driver preferences, road/traffic ETA, airport rules, wheelchair/luggage requirements and tenant-specific policies.

## Safety and reliability rules

- Never dispatch from an uncertain voice transcription without confirmation.
- Never require customer account creation for a basic taxi booking.
- Keep radio/phone fallback during pilot rollout.
- Driver microphone activates only through explicit push-to-talk.
- Driver tracking must stop when off duty.
- Every automated assignment decision must be auditable.
- Failed/ambiguous bookings enter an exception queue instead of silently disappearing.

## Scaling path

**Stage 1:** one taxi base, one WhatsApp number, 5-20 drivers.

**Stage 2:** multiple branded bases on one platform.

**Stage 3:** optional cross-base overflow: a base can request another participating base when it has no cars.

**Stage 4:** customer-facing SnapNest network that routes to the best participating base while preserving commercial rules agreed with operators.
