# Taking the photo from your phone

## The need

A guest who has scanned the "choose your layout" QR is already looking at
their phone. Rather than making them walk back to the booth or reach for the
arcade button, the same page lets them **start the shoot from there** — and
then shows them the finished photo without a second scan.

There is no separate "remote start" QR on the kiosk. The kiosk shows exactly
two QRs (choose your layout, and the "missed your QR?" recovery); phone
triggering lives inside the layout page.

## The flow

1. Scan the layout QR → `GET /choose` (see [`overlays.md`](overlays.md)).
2. Tap a layout tile → it's recorded and highlighted; the page stays put.
3. Tap **📸 Take the photo** → `GET /shoot`:
   - calls the same `BoothView.requestStart()` the arcade button uses,
   - if it **started**: records what the most recent photo was (so step 4 can
     tell when *this* shoot's photo lands) and redirects to `GET /shoot/wait`,
   - if the booth was **busy** (usually the previous photo's result screen is
     still on the kiosk): shows a short "still showing the last photo" page
     with a link back to `/choose` — it does *not* enter the wait loop for a
     photo that isn't coming.
4. `/shoot/wait` renders a "your photo is on its way…" page that
   meta-refreshes every 2s. As soon as a newer photo than the one from step 3
   exists, it redirects to `/p/{name}` — the guest sees their photo, with a
   **Download** button and a secondary **📸 Another photo** link back to
   `/choose`. No JavaScript; it's a server-driven poll. It gives up after
   ~25s with a message that links back to `/choose`.

The **📸 Another photo** link matters: without it a guest naturally hits the
browser Back button, which lands on the stale `/shoot/wait` page. That page
redirects itself to `/choose` on its next refresh, but the explicit link
avoids the detour.

The **📸 Take the photo** button (and the `/shoot` route) only exist when
`remote_start.enabled=true` (the default). With it `false`, the layout page
has no button and `/shoot` answers `404`.

## The trade-off

`/shoot` is reachable by **anyone who has the tunnel URL** — the download
server is only ever exposed through the public Cloudflare tunnel, so that
means anyone on the internet who has seen a QR. A shoot triggered this way
is exactly as real as one from the physical button; there's no check that
whoever tapped it is standing in front of the camera. A single
`AtomicBoolean` in `BoothView` still guarantees only one shoot runs at a
time, whichever trigger fired.

For an attended booth that's fine. For an **unattended** booth where a stray
remote trigger would burn a shot with nobody in frame, set
`remote_start.enabled=false`.
