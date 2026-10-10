# SnapNest Dispatch UI/UX Standard

This document is the visual and interaction source of truth for the SnapNest Dispatch driver app and Control Center.

## Product character

SnapNest Dispatch is an operational mobility product, not a generic SaaS dashboard and not an Uber clone.

The visual target is:
- premium but restrained
- fast to scan while working
- map-first for drivers
- operations-first for dispatchers
- SnapNest navy/orange branding with semantic status colors
- white-label friendly for tenant branding

## Non-negotiable anti-patterns

Do not ship any of the following unless there is a concrete product reason:
- card-on-card-on-card layouts
- excessive rounded rectangles
- every label inside a pill
- large marketing headlines inside operational screens
- paragraphs explaining what obvious controls do
- decorative gradients used without hierarchy purpose
- random glows, floating blobs, or ornamental shapes
- emoji used as interface icons
- arbitrary icon families mixed together
- fake charts, fake earnings, fake metrics, fake social proof
- duplicated status information in multiple parts of the same screen
- identical visual weight for primary and secondary actions
- borders around every surface
- glassmorphism on every container
- unnecessary all-caps text
- oversized logo repetition
- generic AI copy such as “Ready for work”, “Dispatch is watching your live position”, or other explanatory filler when status can be shown directly

## Driver app

### Home
- Map occupies the majority of the viewport.
- One operational bottom surface at a time.
- Driver identity is compact and dismissible/secondary.
- Availability is represented by state and one clear action.
- Floating PTT remains accessible without competing with trip actions.
- Use actual operational data only.

### Trip offer
Show only what helps the driver decide:
- pickup
- destination
- pickup distance/ETA when available
- trip distance/ETA when available
- passenger name
- passenger count
- useful notes
- offer timer
- Accept as dominant action
- Decline as secondary action

### Pickup flow
Accepted -> Navigate -> Arrived -> Waiting -> Start Trip.

The Arrived state must show:
- passenger name
- waiting timer
- Call
- Message
- No-show / Cancel
- Start Trip as deliberate slide/confirm action

### Active trip
- Map and route dominate.
- Destination, ETA/distance and navigation are visible.
- Complete Trip is deliberate, not an easy accidental tap.
- Avoid unrelated earnings/profile information while driving.

### Navigation
Bottom-level product navigation is limited to:
- Home
- Activity
- Account

Support and Safety live under Account or contextual controls until usage proves they deserve primary navigation.

## Floating PTT

Collapsed bubble:
- no text
- tenant logo or simplified tenant monogram
- state ring only
- 56–64dp target
- draggable

Interaction:
- short tap -> quick action menu
- hold -> transmit
- drag -> move bubble, never transmit/open menu

Menu actions:
- Go Available
- Go Unavailable
- Current Trip
- Open App
- Settings
- End Shift

State colors:
- green = available
- amber = busy/on job
- blue = transmitting
- muted gray = unavailable/radio busy
- red = offline/error

## Login

Login is intentionally minimal:
- subtle location/mobility background
- one restrained glass surface only
- tenant/SnapNest identity
- email
- password
- sign in
- optional keep signed in

No product-tour copy, giant slogans, multiple cards, or decorative vehicle illustrations that look synthetic.

## Dispatcher Control Center

Desktop-first web/PWA.

Persistent structure:
- left navigation
- operational header
- live fleet summary
- large live map
- queue/work panel

Primary modules:
- Overview
- Bookings
- Drivers
- Trips
- Customers
- Support
- Analytics
- Settings/Admin

The Overview is not a finance dashboard. It exists to answer:
- What needs a driver?
- Which cars are available?
- Which trips are active?
- What is late or at risk?
- Who should receive the next job?

## Spacing and hierarchy

- Use an 8dp spacing system.
- Mobile horizontal padding: normally 16–20dp.
- Desktop panel gaps: normally 12–16px.
- Prefer whitespace/proximity over borders.
- One primary action per operational state.
- Secondary actions should visibly recede.
- Reserve orange for important SnapNest actions/highlights, not every control.
- Use semantic colors only for real state.

## Typography

Use Inter or a platform-equivalent sans family.

Typical mobile hierarchy:
- title: 24–28sp
- section/action heading: 18–22sp
- body: 14–16sp
- metadata: 11–13sp

Avoid excessive letter spacing and all-caps. All-caps is reserved for very small operational labels where it genuinely improves scanning.

## Icons

Use one coherent vector icon family.
- no emoji
- no text symbols standing in for icons in production
- no decorative icons without action/meaning

## Copy

Copy must be short, localizable and operational.

Prefer:
- Available
- New trip
- 4 min to pickup
- Arrived
- Waiting 02:14
- Start trip
- Complete trip

Avoid:
- “You're online! Waiting for your next dispatch.”
- “Dispatch is watching your live position while you're available.”
- long instructional paragraphs on the working screen

## White-label rules

Tenant-configurable:
- operator/service name
- compact logo/monogram
- optional primary accent within contrast limits
- customer-facing wording

SnapNest attribution stays discreet:
“Powered by SnapNest Digital Solutions”.

The product must remain visually coherent even when a tenant supplies a weak or complex logo; use a simplified mark inside small controls.
