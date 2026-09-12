# Customising per event — without a rebuild

Every setting lives in an **event bundle** under `events/<id>/`, and
`events/_active` names the one the booth loads. Everything below is read at
**startup** — change a file, restart the app, no recompile. The app also runs
with none of it, on bundled defaults. See [`events.md`](events.md) for the
bundle layout, the `_active` pointer and the legacy-`config/` migration.

In the examples below, `events/<id>/` is the active event's folder.

## Get a starting point

```bash
./gradlew seedConfig
```

Writes the bundled defaults you can override into `events/default/` as
editable files (existing ones are left untouched):

- `events/default/i18n/en.properties`, `.../i18n/nl.properties`
- `events/default/web/photo.mustache`, `choose.mustache`, `message.mustache`, `style.css`
- `events/default/kiosk/kiosk.css`

`events/` is git-ignored — it's per-booth / per-event.

## What you can change

| What | Bundled default | Override (in the active event bundle) | Notes |
| --- | --- | --- | --- |
| **All settings** (pins, countdown, ports, …) | built-in defaults | `events/<id>/photobooth.properties` | copy from `config/photobooth.properties.example` |
| **Which camera** | camera `0` (Pi) / macOS default (webcam) | `camera.type` (`pi`/`webcam`/`placeholder`/`auto`) + `camera.device` | `camera.device` is a camera index for `pi` (`rpicam-still --list-cameras`, blank = `0`), a device name for `webcam` (`imagesnap -l`). The self-view stream uses the same camera |
| **Wording** (kiosk + phone) | `src/main/resources/i18n/<lang>.properties` | `events/<id>/i18n/<lang>.properties` | per key; missing keys fall back to English |
| **A whole new language** | — | `events/<id>/i18n/<code>.properties` + `ui.language=<code>` | unbundled languages are fine; untranslated keys fall back to English |
| **"Smile!" shout** | `smile_words` in the i18n files | same key in `events/<id>/i18n/<lang>.properties` | comma-separated; one picked at random per shoot — see [`i18n.md`](i18n.md) |
| **"Smile!" hold** | 1200 ms | `smile.hold.ms` | how long "Smile!" stays up after the countdown before the result screen |
| **Phone page markup** | `src/main/resources/web/*.mustache` | `events/<id>/web/*.mustache` | drop a file to replace a page — see [`phone-pages.md`](phone-pages.md) |
| **Phone page styling / logo / brand name** | `src/main/resources/web/style.css` | `events/<id>/web/style.css`, `.../web/logo.*`, `web.brand.name` | — |
| **Kiosk screen styling** (colours, fonts, sizes, shadows) | `src/main/resources/kiosk/kiosk.css` | `events/<id>/kiosk/kiosk.css` | layered on top; JavaFX CSS — see [`screen-decoration.md`](screen-decoration.md) |
| **Full-screen** (per screen) | on | `screen.booth.fullscreen` / `screen.picker.fullscreen` (or the `PHOTOBOOTH_WINDOWED` env var to force both windowed) | real `Stage.isFullScreen`, applied after the window is on its display; when off, `window.width` / `window.height` set the size, or blank = auto (~80% of the display) |
| **Border around the self-view** | 46 px white | `.kiosk-stage` in `events/<id>/kiosk/kiosk.css` (`-fx-padding` = width, `-fx-background-color` = colour; `0` = edge-to-edge) | — |
| **Second operator screen** | off | `secondscreen.enabled` + `screen.booth.display` / `screen.picker.display` | layout gallery + red shoot button on a 2nd display — see [`second-screen.md`](second-screen.md) |
| **Which QRs show, and where** | both on, in the bottom corners | `kiosk.qr.layout.enabled` / `.position`, `kiosk.qr.recovery.enabled` / `.position`, `kiosk.taphint.position` | position is one of `top-left … bottom-right`, plus `center-left` / `center-right` |
| **Idle-screen decoration** (image layers + a text title) | none | `events/<id>/decor/*` + `decor.<n>.*` / `decor.title.*` keys | numbered layers with position / size / rotation — see [`screen-decoration.md`](screen-decoration.md) |
| **The capture overlay(s)** | `events/<id>/overlay.png` | `events/<id>/overlays/` + `overlay.selection.*` | see [`overlays.md`](overlays.md) |
| **Photo output folder** | `events/<id>/data/<date>/` | `data.dir`, `data.subdir` | relative to the bundle unless absolute |
| **Logging** | bundled `logback.xml` | `-Dlogback.configurationFile=/path/to/logback.xml` (JVM arg) | rarely needed; not per event |

## Preview without the booth

- **Phone pages:** `./gradlew renderPhonePages` → `build/phone-pages-preview/`
  (reads the active event's `web/`).
- **Kiosk styling:** edit `events/<id>/kiosk/kiosk.css`, restart the app.
