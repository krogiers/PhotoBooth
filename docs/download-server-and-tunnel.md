# The download server and the Cloudflare tunnel

## The need

A guest scans a QR and their phone has to open *their* merged photo. You
can't assume the guest is on the venue's Wi-Fi (there often isn't one, or it
needs a login page), and you don't want them switching networks in front of
the booth. So the download server is never reached directly: it runs on the
booth machine and a Cloudflare quick tunnel puts it on the public internet,
so the phone just uses its own mobile data.

## The server — [`DownloadServer.kt`](../src/main/kotlin/com/photobooth/server/DownloadServer.kt)

An embedded Ktor/Netty server on `server.port` (default `8080`). It serves
the merged-photos directory and a handful of small phone-facing pages:

| Route | What it does |
| --- | --- |
| `/p/{name}` | A page with a photo preview and a **Download** button — this is what a download QR points at. A bare image link would just open full-screen on most phones. |
| `/photos/{name}` | The raw merged image (used by the `/p` page's `<img>`). |
| `/download/{name}` | Same file with `Content-Disposition: attachment` so the button actually saves it. |
| `/last` | Redirects to whichever photo was taken most recently — the idle screen's "missed your QR?" recovery. A single ever-changing slot, not a gallery: it only helps a guest if no *later* group has shot since. Photo *files* are never deleted (kept under `data/<date>/`), but every guest-facing URL is a `trycloudflare.com` host that dies when the booth shuts down — only a photo the guest actually **downloaded** survives past the event. |
| `/choose`, `/choose/{name}`, `/overlays/{name}` | Phone-side layout picker — only mounted when `overlay.selection.enabled=true`. See [`overlays.md`](overlays.md). |
| `/shoot`, `/shoot/wait` | Start the countdown from the phone (the button on `/choose`), then poll until this shoot's photo is ready and redirect to it. Only when `remote_start.enabled=true`. See [`remote-start.md`](remote-start.md). |
| `/web/{file}` | The phone pages' stylesheet / logo / fonts, `config/web/` winning over the bundled copy. See [`phone-pages.md`](phone-pages.md). |
| `/preview` | Renders every phone page with sample data, for tweaking the styling on a phone without taking a real photo. |

The HTML pages themselves are Mustache templates, rebrandable per event —
see [`phone-pages.md`](phone-pages.md).

**Code layout.** `DownloadServer.kt` only owns the Netty engine and a
`PhoneRoutingContext` (the shared dependencies). Each route group is its own
file — `PhotoRoutes.kt`, `ShootRoutes.kt`, `OverlayRoutes.kt`,
`WebAssetRoutes.kt`, `PreviewRoutes.kt` — as a `Routing.xxxRoutes(ctx)`
extension, mounted in `DownloadServer.start()`. `RouteSupport.kt` holds the
shared `respondPage` / `serveFileOrNotFound` helpers; `ChooserPage.kt` builds
the `/choose` page model.

Served files carry `Cache-Control: no-store` so Cloudflare's edge doesn't
keep a copy of what are otherwise private event photos. Every path parameter
is resolved with `File(dir, name).normalize()` and rejected unless it stays
inside its directory — those routes are reachable by anyone on the internet.

## The tunnel — [`CloudflareTunnel.kt`](../src/main/kotlin/com/photobooth/server/CloudflareTunnel.kt)

On startup the app runs:

```bash
cloudflared tunnel --url http://localhost:<server.port>
```

and blocks (up to ~20s) until `cloudflared` prints its
`https://<words>.trycloudflare.com` address. That address becomes the base
URL for the two guest-facing QRs the kiosk shows — `/choose` and `/last` —
generated once at startup into `data/`.

Quick tunnels are anonymous (no Cloudflare account) and get a fresh random
URL every run — fine for a one-day event, not a stable address. If
`cloudflared` never reports a URL within the timeout the app fails loudly
rather than starting with dead QRs.

On shutdown the tunnel is `destroy()`d, then `destroyForcibly()`d if it
hasn't exited within 3s.

## Requirements

- `cloudflared` on `PATH`. macOS: `brew install cloudflared`. Raspberry Pi
  (arm64): install from Cloudflare's package repo or their GitHub releases.
- Outbound internet on the booth machine — venue Wi-Fi with internet,
  Ethernet, or a mobile hotspot dongle. Only the booth needs it; guests
  don't.
- Allow a few extra seconds of startup while the tunnel comes up.
