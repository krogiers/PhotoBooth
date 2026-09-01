# Customising per event — without a rebuild

Everything below is read at **startup**. Change a file, restart the app —
you never need to recompile or repackage. The app also runs with none of it,
using bundled defaults.

## Get a starting point

```bash
./gradlew seedConfig
```

Writes the bundled defaults you can override into `config/` as editable
files (existing ones are left untouched):

- `config/i18n/en.properties`, `config/i18n/nl.properties`
- `config/web/photo.mustache`, `choose.mustache`, `message.mustache`, `style.css`
- `config/kiosk/kiosk.css`

Everything under `config/` is git-ignored — it's per-booth / per-event.

## What you can change

| What | Bundled default | Override | Notes |
| --- | --- | --- | --- |
| **All settings** (pins, countdown, ports, …) | built-in defaults | `config/photobooth.properties` | copy from `config/photobooth.properties.example` |
| **Which camera** | backend default | `camera.type` (`pi`/`webcam`/`placeholder`/`auto`) + `camera.device` | `camera.device` is a camera index for `pi` (`rpicam-still --list-cameras`), a device name for `webcam` (`imagesnap -l`) |
| **Wording** (kiosk + phone) | `src/main/resources/i18n/<lang>.properties` | `config/i18n/<lang>.properties` | per key; missing keys fall back to English |
| **A whole new language** | — | `config/i18n/<code>.properties` + `ui.language=<code>` | unbundled languages are fine; untranslated keys fall back to English |
| **"Smile!" shout** | `smile_words` in the i18n files | same key in `config/i18n/<lang>.properties` | comma-separated; one picked at random per shoot — see [`i18n.md`](i18n.md) |
| **Phone page markup** | `src/main/resources/web/*.mustache` | `config/web/*.mustache` | drop a file to replace a page — see [`phone-pages.md`](phone-pages.md) |
| **Phone page styling / logo / brand name** | `src/main/resources/web/style.css` | `config/web/style.css`, `config/web/logo.*`, `web.brand.name` | — |
| **Kiosk screen styling** (colours, fonts, sizes, shadows) | `src/main/resources/kiosk/kiosk.css` | `config/kiosk/kiosk.css` | layered on top; JavaFX CSS — see [`screen-decoration.md`](screen-decoration.md) |
| **Full-screen** | on | `window.fullscreen` (or the `PHOTOBOOTH_WINDOWED` env var to force windowed) | `window.width` / `window.height` set the non-full-screen size |
| **Second operator screen** | off | `secondscreen.enabled` + `screen.booth.display` / `screen.picker.display` | layout gallery + red shoot button on a 2nd display — see [`second-screen.md`](second-screen.md) |
| **Which QRs show, and where** | both on, centred against each edge | `kiosk.qr.layout.enabled` / `.position`, `kiosk.qr.recovery.enabled` / `.position`, `kiosk.taphint.position` | position is one of `top-left … bottom-right`, plus `center-left` / `center-right` |
| **Idle-screen decoration** (image layers + a text title) | none | `config/decor/*` + `decor.<n>.*` / `decor.title.*` keys | numbered layers with position / size / rotation — see [`screen-decoration.md`](screen-decoration.md) |
| **The capture overlay(s)** | `config/overlay.png` | `config/overlays/` + `overlay.selection.*` | see [`overlays.md`](overlays.md) |
| **Photo output folder** | `data/<date>/` | `data.dir`, `data.subdir` | one folder per event |
| **Logging** | bundled `logback.xml` | `-Dlogback.configurationFile=/path/to/logback.xml` (JVM arg) | rarely needed; not wired to `config/` |

## Preview without the booth

- **Phone pages:** `./gradlew renderPhonePages` → `build/phone-pages-preview/`.
- **Kiosk styling:** edit `config/kiosk/kiosk.css`, restart the app.
