# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kotlin/JVM photobooth application for a Raspberry Pi. Flow: an arcade
button or touchscreen tap starts a countdown → "lighting ok" LEDs turn on →
the Pi camera takes a photo → the photo is merged with a branded overlay →
guests scan a QR code to download the merged photo. The original,
un-merged photo is always kept alongside it.

The project is currently a first working scaffold (see README.md "Status"):
all pieces build and are wired together, but pin numbers, camera settings,
and UI styling are still placeholders to be tuned against real hardware.
Open bugs from the last review are tracked in
[`docs/known-issues.md`](docs/known-issues.md).

## Commands

```bash
./gradlew build          # compile + test + package
./gradlew run             # run the active event (events/_active), full-screen — the real booth
./gradlew run -Pdev       # run the events/dev bundle, windowed — Mac / laptop dev
./gradlew run -Pevent=x   # run the events/x bundle;  -Pwindowed forces a window
./gradlew test            # run all tests
./gradlew test --tests "com.photobooth.imaging.OverlayCompositorTest"   # single test class
./gradlew shadowJar      # build ONE runnable jar, build/libs/photobooth.jar (java -jar it)
./gradlew installDist     # alternative: a runnable distribution under build/install/photobooth/
./gradlew renderPhonePages # render the phone-facing pages (sample data) to build/phone-pages-preview/
./gradlew seedConfig      # copy the bundled i18n / web / kiosk defaults into events/default/ for editing
```

`-Pdev` / `-Pevent=x` set the `PHOTOBOOTH_EVENT` env var, which
[`EventStore`](src/main/kotlin/com/photobooth/config/EventStore.kt) honours
over `events/_active` (also as the `photobooth.event` system property, e.g. on
the Pi binary). `events/dev/` is a windowed, no-hardware bundle on its own
`admin.port` / `server.port` so it can run alongside the real one.

On the Raspberry Pi itself: copy the source over (no `git` needed — `scp`/
`rsync` is fine), then `./gradlew shadowJar` and `java -jar
build/libs/photobooth.jar` **on the Pi's console**, not over SSH — JavaFX
needs the local display. Build it **on the Pi**, not on a dev machine:
JavaFX's jars are OS/architecture-specific and the Gradle plugin managing
them always picks the *build* machine's platform, with no override — a jar
built on a Mac will not run on the Pi. Once built there, that one jar file
is a plain artifact you can copy to another Pi with no rebuild. Autostart on
boot (desktop autologin + a systemd `--user` service,
[`packaging/pi/photobooth.service`](packaging/pi/photobooth.service)) is in
[`docs/pi-deployment.md`](docs/pi-deployment.md).

**Config is per-event.** Each event is a self-contained bundle under
`events/<id>/` and `events/_active` (one line) names the one the booth
loads — see [`docs/events.md`](docs/events.md) and
[`config/EventBundle.kt`](src/main/kotlin/com/photobooth/config/EventBundle.kt).
A bundle holds `photobooth.properties` (every key — GPIO pins, countdown,
`server.port`, …), `overlay.png`, and the optional per-event override
folders `overlays/`, `decor/`, `i18n/<lang>.properties`, `web/`,
`kiosk/kiosk.css`, plus the event's `data/` photos. Path-valued keys resolve
**relative to the bundle**. The bundled defaults in `src/main/resources/`
stay the fallback under everything. On first run a legacy flat `config/` is
copied into `events/default/`. `./gradlew seedConfig` seeds the override
files; [`docs/customising.md`](docs/customising.md) is the full list.

## Architecture

Each concern is its own class behind a narrow interface so hardware/backends
can be swapped without touching orchestration logic. This section is the map;
each subsystem's own needs and behaviour are written up under
[`docs/`](docs/) (linked per bullet below).

Packages that have an interface plus swappable implementations keep the
interface(s) in the package root and every implementation one level down in
`impl/` — so `camera/` and `gpio/` below.

- `camera/` — the `Camera` (`capture(File): File`) and `PreviewSource`
  interfaces; `camera/impl/` has the `pi` / `webcam` / `placeholder` camera
  backends plus the `PreviewSource` stack for the live self-view
  (`RpicamPreviewStream` / `FfmpegPreviewStream` / the `PreviewLoop`
  still-grab fallback). `LibcameraCamera` shells out to the `rpicam-still`
  CLI (Bookworm's `libcamera` stack) via `ProcessBuilder`. See
  [`docs/camera-and-preview.md`](docs/camera-and-preview.md).
- `gpio/` — `Gpio` interface (button listener + LED); `gpio/impl/` has
  `Pi4jGpioController`, which wraps Pi4J v2 for the arcade start button
  (digital input, pulled up, active-low) and the "lighting ok" LED (digital
  output), and `SimulatedGpioController`, the dev-machine no-op. Note the
  Pi4J v2 API
  shape used here: `Pi4J.newAutoContext()` +
  `pi4j.create(DigitalInput.newConfigBuilder(pi4j)…)` (not `din()`/`dout()`
  accessor methods), and `addListener(DigitalStateChangeListener { ... })`
  with an explicit SAM constructor (a trailing lambda doesn't work against
  its vararg parameter). Details in [`docs/gpio.md`](docs/gpio.md).
- `imaging/OverlayCompositor.kt` — merges a captured photo with the overlay
  PNG using plain `java.awt`/`ImageIO` (no extra dependency). Never mutates
  the original file. `session/OverlaySelection.kt` adds the optional
  phone-side overlay picker — see [`docs/overlays.md`](docs/overlays.md).
- `qrcode/QrCodeGenerator.kt` — wraps ZXing to render a QR PNG for the
  download URL.
- `i18n/Strings.kt` — every guest-facing string, in layered `.properties`
  files (bundled `en`/`nl`, plus the active event's `i18n/` overrides), never
  hardcoded. `asMap()` exposes the merged set for the admin panel. See
  [`docs/i18n.md`](docs/i18n.md).
- `server/` — the embedded Ktor/Netty server for everything a guest's phone
  talks to (`/photos`, `/download`, `/p/{name}`, `/last`, `/choose`,
  `/shoot`, `/shoot/wait`, `/web/{file}`, `/preview`). `DownloadServer.kt`
  owns the Netty engine and a `PhoneRoutingContext` (the shared deps);
  routes are one concern per file — `PhotoRoutes` / `ShootRoutes` /
  `OverlayRoutes` / `WebAssetRoutes` / `PreviewRoutes`, each a
  `Routing.xxxRoutes(ctx)` extension, with `RouteSupport.kt` and
  `ChooserPage.kt` for shared plumbing. Guests never hit it directly:
  `CloudflareTunnel.kt` shells out to `cloudflared tunnel` to expose it on
  the public internet, and that `https://<words>.trycloudflare.com` address
  is what every guest-facing QR resolves to. Routes and tunnel lifecycle in
  [`docs/download-server-and-tunnel.md`](docs/download-server-and-tunnel.md).
- `server/admin/` — the LAN **admin panel** (`admin.enabled`). A **separate**
  Netty engine on `admin.port` (never the tunnelled `server.port`), behind
  HTTP Basic auth (`admin` + `admin.password`). `AdminServer.kt` +
  `adminModule()` wire auth + `adminRoutes` (dashboard / events / text,
  read-only) + `settingsRoutes` (a typed GET form + POST that validates
  against `SettingsSchema` and writes only real deltas via `SettingsFile`,
  leaving comments / order / `decor.*` intact) + `overlayAdminRoutes`
  (gallery + multipart upload / delete / set-default, writing
  `<event>/overlays/` and `overlay.file`; PNG-magic + 20 MB checks, filename
  sanitised). A *live* change fires `AdminContext.reloadHook` →
  `PhotoBoothComponent.onReloadRequested` → `PhotoBoothApp.mountBooth`, which
  rebuilds `BoothView` from fresh config without touching the
  camera/GPIO/server (`PhotoSession.config` and `OverlaySelection.defaultOverlay`
  are swapped too). Other keys are reported as "restart to apply".
  `AdminPages.kt` is a dependency-free HTML renderer. Built and owned by
  `PhotoBoothComponent`. See [`docs/events.md`](docs/events.md).
- `server/PhonePages.kt` — renders the phone-facing HTML from Mustache
  templates (`src/main/resources/web/` defaults, `<event>/web/` per-event
  overrides, re-read per request), serves their assets at `/web/{file}`, and
  backs `/preview`. See [`docs/phone-pages.md`](docs/phone-pages.md).
- `session/PhotoSession.kt` — orchestrates one full shoot (countdown → LED
  → capture → merge → QR) by composing the above. Deliberately has **no**
  JavaFX/UI dependency; it's driven by three callback properties
  (`onCountdownTick`, `onBeforeCapture`, `onPhotoReady`) so it can be
  unit-tested or reused under a different UI. Capture runs on a
  `CompletableFuture` alongside the countdown loop; `onBeforeCapture` (wired
  to `previewSource.stop()`) must free the camera before it — see
  [`docs/camera-and-preview.md`](docs/camera-and-preview.md).
- `util/DaemonExecutors.kt` — `daemonExecutor` / `daemonScheduler` /
  `daemonPool` factories: every background thread in the app is a named
  daemon from one of these, owned by its component and shut down there
  (`CloudflareTunnel`, the preview streams, `PreviewLoop`, `BoothView`).
- `PhotoBoothComponent.kt` — the composition root: builds the whole non-UI
  graph (config, camera, GPIO, download server, and the Cloudflare tunnel,
  whose startup blocks a few seconds), owns `close()`, and holds the
  `select*` functions that make the Linux-vs-dev-machine backend choices.
- `Main.kt` (`PhotoBoothApp`) — a thin JavaFX shell: `start()` shows a
  loading screen at once, runs `PhotoBoothComponent.build()` on a background
  executor (so the FX thread never blocks), then `installBooth` wires the
  component→UI callbacks once (indirecting through the swappable `boothView` /
  `pickerView` fields) and calls `mountBooth`, which builds `BoothView` from
  freshly-loaded config — or an error screen if the build throws. `mountBooth`
  runs again on every *live* admin settings change. Owns every `Stage`: the booth window
  on `screen.booth.display`, and — when `secondscreen.enabled` — a second
  window on `screen.picker.display` (skipped, not fallen back, if that
  display is absent). Fullscreen per screen (`screen.booth.fullscreen` /
  `screen.picker.fullscreen`) is real `Stage.isFullScreen`, applied *after*
  `show()` on the target display so it covers it edge to edge; only one Stage
  can hold it, so `installPicker` re-asserts the booth's flag after bringing
  the picker up. Windowed size is `window.width/height` or, blank, ~80% of
  the display. Any window's close, `stop()`, and a JVM shutdown hook all
  funnel through one idempotent `teardown()`. See [`docs/second-screen.md`](docs/second-screen.md).
- `ui/BoothView.kt` — the JavaFX kiosk UI (idle/countdown/result states;
  two idle QRs in the bottom corners — layout picker left, `/last` recovery
  right — positions/on-off from `KioskLayout`; an optional per-event
  `DecorConfig` layer of bunting / graphic / title behind them). Wires the
  `Gpio` button press, a touchscreen tap, and the phone's "Take the photo"
  button (`/shoot`, [`docs/remote-start.md`](docs/remote-start.md)) to the
  same `PhotoSession` run. The self-view sits in a framed box (`.kiosk-stage`
  padding = the border around the picture) and each frame reuses one
  `PixelBuffer` surface instead of a fresh `Image`.
- `ui/PickerView.kt` — the optional second-screen UI: the layout gallery + a
  red "take the photo" button, no download, no result view. Reuses
  `OverlaySelection` and calls `BoothView.requestStart()`.
- `config/EventStore.kt` + `config/EventBundle.kt` — the per-event layout.
  `EventStore` resolves `events/_active` to an `EventBundle` (the active
  event's folder), lists bundles for the admin panel, sets the active one,
  and on first run migrates a legacy flat `config/` into `events/default/`.
  `EventBundle` exposes the bundle's sub-paths and `resolve()` for
  bundle-relative config values. Kept free of JavaFX types.
- `config/AppConfig.kt` (+ `config/DecorConfig.kt`, `config/KioskLayout.kt`) —
  `load(bundle)` reads `<bundle>/photobooth.properties` (falling back to
  defaults so the app runs on a dev machine without any hardware or bundle),
  resolves path-valued keys against the bundle, derives a per-session folder
  `<bundle>/data/<date>/` (or `data.subdir`) with `originals/` and `merged/`,
  and parses the `decor.*` and `kiosk.*.position` idle-screen keys. Carries
  `eventDir` / `eventId`. Kept free of JavaFX types.

Data flow through `PhotoSession.run()`: countdown ticks drive the UI via
`onCountdownTick`, then the original photo is written to
`<event>/data/<date>/originals/<timestamp>.jpg` and the merged copy to
`<event>/data/<date>/merged/<timestamp>.png` (this is what `DownloadServer` serves).
`onPhotoReady` hands the UI the merged file, the download QR **as PNG bytes**
(QR codes are never written to disk — they only encode the current run's
tunnel host), and the resolved download URL.
