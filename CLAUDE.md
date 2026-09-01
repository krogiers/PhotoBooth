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
./gradlew run             # run the JavaFX app locally (needs a local display)
./gradlew test            # run all tests
./gradlew test --tests "com.photobooth.imaging.OverlayCompositorTest"   # single test class
./gradlew installDist     # build a runnable distribution under build/install/photobooth/
./gradlew renderPhonePages # render the phone-facing pages (sample data) to build/phone-pages-preview/
./gradlew seedConfig      # copy the bundled i18n / web / kiosk defaults into config/ for editing
```

On the Raspberry Pi itself, use `installDist` and run
`build/install/photobooth/bin/photobooth` directly on the Pi's console —
JavaFX needs the local display, so this won't work over a plain SSH/X11
forward session.

Config lives in `config/photobooth.properties` (git-ignored; copy from
`config/photobooth.properties.example`), covering GPIO pin numbers,
countdown length, and the local `server.port` the download server listens
on. `config/overlay.png` is the transparent PNG merged onto every capture.
Optional per-event overrides — no rebuild, read at startup — also layer in
from `config/`: `config/i18n/<lang>.properties` (wording + extra languages),
`config/web/` (phone-page templates + CSS), `config/overlays/` (selectable
overlays), `config/decor/` (idle-screen bunting / graphic / title font),
`config/kiosk/kiosk.css` (all kiosk-screen styling). `./gradlew seedConfig`
seeds those from the bundled defaults;
[`docs/customising.md`](docs/customising.md) is the full list.

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
  files (bundled `en`/`nl`, plus optional `config/i18n/` overrides), never
  hardcoded. See [`docs/i18n.md`](docs/i18n.md).
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
- `server/PhonePages.kt` — renders the phone-facing HTML from Mustache
  templates (`src/main/resources/web/` defaults, `config/web/` per-event
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
  executor (so the FX thread never blocks), then installs `BoothView` — or
  an error screen if the build throws. Owns every `Stage`: the booth window
  on `screen.booth.display`, and — when `secondscreen.enabled` — a second
  window on `screen.picker.display` (skipped, not fallen back, if that
  display is absent). See [`docs/second-screen.md`](docs/second-screen.md).
- `ui/BoothView.kt` — the JavaFX kiosk UI (idle/countdown/result states,
  full-screen; two idle QRs, one per screen edge — layout picker left,
  `/last` recovery right; an optional per-event `DecorConfig` layer of
  bunting / graphic / title behind them). Wires the `Gpio` button press, a
  touchscreen tap, and the phone's "Take the photo" button (`/shoot`,
  [`docs/remote-start.md`](docs/remote-start.md)) to the same `PhotoSession`
  run. The self-view fills the real display (fit sizes bound to the root),
  and each frame reuses one `PixelBuffer` surface instead of a fresh `Image`.
- `ui/PickerView.kt` — the optional second-screen UI: the layout gallery + a
  red "take the photo" button, no download, no result view. Reuses
  `OverlaySelection` and calls `BoothView.requestStart()`.
- `config/AppConfig.kt` (+ `config/DecorConfig.kt`, `config/KioskLayout.kt`) —
  loads `config/photobooth.properties` (falling back to defaults so the app
  runs on a dev machine without any hardware attached), derives a per-session
  folder `data/<date>/` (or `data.subdir`) with `originals/` and `merged/`
  under it so back-to-back events stay separate, and parses the `decor.*` and
  `kiosk.*.position` idle-screen keys. Kept free of JavaFX types.

Data flow through `PhotoSession.run()`: countdown ticks drive the UI via
`onCountdownTick`, then the original photo is written to
`data/<date>/originals/<timestamp>.jpg` and the merged copy to
`data/<date>/merged/<timestamp>.png` (this is what `DownloadServer` serves).
`onPhotoReady` hands the UI the merged file, the download QR **as PNG bytes**
(QR codes are never written to disk — they only encode the current run's
tunnel host), and the resolved download URL.
