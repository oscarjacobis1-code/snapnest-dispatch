# Dispatch and driver UI reference review — 2026-10-10

This is a workflow reference, not a visual copy or proof of field usability. Validate SnapNest on a dispatcher laptop and a driver's real phone in Guyana.

| Product / evidence | Useful pattern | SnapNest decision |
| --- | --- | --- |
| [TaxiCaller reviews](https://www.capterra.com/p/190946/TaxiCaller/reviews/) (4.8/5 from **8** Capterra reviews; ease of use 4.9) and [dispatch console](https://www.taxicaller.com/en/features/dispatch-system) | Map, booking overview, driver tracking and communications share an operations workspace. The small review sample cannot prove the interface is best. | Keep the urgent booking queue and fleet map together; place customer contact and manual assignment on the booking itself. |
| [TaxiCaller driver guide](https://www.taxicaller.com/en/quick-guides/driver-app) and [Google Play listing](https://play.google.com/store/apps/details?id=com.taxicaller.dispatch) | Booking details and the next trip action stay visible; navigation and preset messages reduce switching. | Give the driver a single prominent next action, readable pickup and destination, and a stable way back from navigation. |
| [iCabbi Drive](https://icabbi.com/platform/drive/) and [Google Play reviews](https://play.google.com/store/apps/details?id=com.icabbi.driver.app) | One-screen upcoming trips and communication are useful ideas, but its driver app listing showed **2.9/5 from about 1.54K reviews**; complaints included stale jobs and offers while unavailable. | Treat stale state, wrong offers and availability control as core UX defects. Never call an attractive screenshot evidence of reliable driver operation. |
| [Onde Driver reviews](https://play.google.com/store/apps/details?id=com.multibrains.taxi.driver) | Drivers report pain when navigation requires awkward app switching or offers arrive while offline. | Make duty state obvious and preserve the active job when navigation hands off. |

## Next field-use UI checks

1. At 1366×768, dispatchers can see waiting bookings, driver availability and the fleet map without hunting across pages.
2. A dispatcher can assign, reassign, cancel and contact the customer from a booking with visible state and clear confirmation.
3. On a real Android phone, a driver can read pickup, destination and passenger notes and act with one hand; a pending offer never survives expiry as if live.
4. Offline and stale GPS states are explicit. The UI never shows a made-up ETA or marks a WhatsApp draft as sent.
5. Test with real operators after a long shift; the reference products' reviews are clues, not a substitute for SnapNest field testing.
