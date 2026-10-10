# Security and WhatsApp intake

## What is ready

- The dispatcher Control Center is the operator web app. The separate Android project is the driver app. Both use server-side tenant membership checks in persistent mode. The driver-profile RLS policy limits direct Data API reads to the linked driver or an operator in that tenant.
- Production refuses to start without the Supabase URL and keys, or when dispatch authentication is disabled.
- Only an admin or dispatcher may create a booking through the API in persistent mode. The local memory demo still supports the simulator for development.
- Driver state changes, GPS, offer responses and trip actions require the linked driver in persistent mode. Operator manual assignment and cancellation remain separate actions.
- The customer page prepares a WhatsApp message and opens the configured business number. Opening the link does not create a booking. The customer must tap Send and the base must confirm the ride.
- Meta webhook verification uses a private token. POST requests require HMAC-SHA256 over the **raw** request body with the Meta app secret. Only messages for the configured phone number ID enter the operator inbox. Status events cannot create bookings.
- Operators review text and shared locations in Bookings → WhatsApp inbox, use the booking form to confirm location and destination, then submit. Customer text is displayed as text, not HTML.
- API JSON responses carry `Cache-Control: no-store`. The PWA service worker excludes `/api/` from caching.

## Required setup

In the Render service set:

| Variable | Source |
| --- | --- |
| `WHATSAPP_PUBLIC_NUMBER` | Business WhatsApp number in international format, digits only |
| `WHATSAPP_VERIFY_TOKEN` | Private generated value; use the same value in Meta webhook configuration |
| `WHATSAPP_APP_SECRET` | Meta app secret, kept only on the server |
| `WHATSAPP_PHONE_NUMBER_ID` | Meta Cloud API phone number ID for this taxi base |

Set Meta's webhook callback URL to `https://<your-dispatch-host>/api/whatsapp/webhook`, subscribe to the WhatsApp `messages` field, and test with a real customer message and a shared location. The customer page uses `WHATSAPP_PUBLIC_NUMBER`; without it the button reports that WhatsApp booking is unavailable. The `WHATSAPP_ADAPTER_KEY` is for a separate trusted adapter at `/api/whatsapp/inbound`, not for Meta webhook verification. Never place either secret in the browser or repository.

## Threat model and remaining work

| Goal | Implemented control | Remaining risk |
| --- | --- | --- |
| Confidentiality | Tenant-scoped API reads, role checks, no-store responses, driver-only state filter | Browser tokens are currently stored in localStorage. A future XSS could steal them; move to server-managed HttpOnly sessions and self-host external scripts before high-risk production use. |
| Integrity | Signed Meta webhooks, operator confirmation before dispatch, role checks, validated pickup time | WhatsApp inbox retries are deduplicated best-effort in code. A unique database constraint on tenant/channel/external ID is needed before automated dispatch is introduced. |
| Availability | Invalid webhook traffic is rejected before database work; bounded body and message sizes | Render free instance sleep and external WhatsApp/Supabase outages still interrupt intake. Keep radio/phone dispatch as an operational fallback, and configure database backups and alerting. |

The webhook currently **records messages for a human operator**. It does not auto-reply, parse arbitrary chat into a confirmed booking, or send trip status updates. Those require a Meta access token, approved messaging setup, durable conversation state and an explicit customer confirmation flow. Do not advertise automated WhatsApp dispatch until those pieces are tested end to end.

## Pilot security checks

1. Sign in as admin, dispatcher and a linked driver; verify each sees only authorized data and actions. Confirm an unlinked driver is denied.
2. Send a Meta test webhook with an invalid signature and a wrong phone number ID. Confirm neither appears in the inbox.
3. Send a real text and location, review it in the operator inbox, confirm the details with the customer, and create a booking.
4. Run a real trip with a driver device. Verify GPS stops when off duty and the customer is never told a ride is confirmed before the base confirms it.
5. Review backup restore, secret rotation, HTTPS, service uptime alerts and access logs before the first commercial base.
