# PhotoBooth

A Kotlin/JVM photobooth app for the Raspberry Pi. The idle screen shows a
live self-view so guests can see where they're standing; an arcade button or
a touchscreen tap starts a big animated countdown (with "lighting ok" LEDs),
the Pi camera takes a photo, the shot is merged with a branded overlay, and
guests scan a QR code to download it. The original, un-merged photo is always
kept too.

## Hardware assumptions

- Raspberry Pi (with GPIO header) running Raspberry Pi OS Bookworm or newer.
- Pi Camera Module, accessed via `rpicam-still` (the `libcamera` stack that
  ships with Bookworm). A USB webcam or older OS needs a different `Camera`
  backend — see [`docs/camera-and-preview.md`](docs/camera-and-preview.md).
- An arcade button wired to a GPIO pin (pulled up, active-low) and one or
  more LEDs wired to another. Default BCM pins are 17 (button) and 27 (LED) —
  see [`docs/gpio.md`](docs/gpio.md).
- A touchscreen as the Pi's display (the app also runs full-screen without
  one; the on-screen tap target still works with a mouse).
- Outbound internet on the booth machine, for the Cloudflare tunnel guests'
  phones use to reach their photo — see
  [`docs/download-server-and-tunnel.md`](docs/download-server-and-tunnel.md).

## Configuration

Copy `config/photobooth.properties.example` to `config/photobooth.properties`
and adjust it — that file is the annotated source of truth for every key.
Replace `config/overlay.png` with your event's transparent PNG overlay.

`config/photobooth.properties`, `config/overlay.png`, `config/overlays/` and
`config/i18n/` are all read at startup and need no rebuild.

## How it works

Each concern is a small class behind a narrow interface, with a real Pi
implementation and a dev-machine stand-in. The details, and what each one
needs, are split out per subsystem:

| Doc | Covers |
| --- | --- |
| [`docs/camera-and-preview.md`](docs/camera-and-preview.md) | Camera backends (`pi` / `webcam` / `placeholder`), the live self-view streams vs. the still-grab fallback, and the capture-vs-preview race |
| [`docs/download-server-and-tunnel.md`](docs/download-server-and-tunnel.md) | The embedded HTTP server, its phone-facing routes (`/p`, `/last`, …), and the Cloudflare quick tunnel that exposes it |
| [`docs/phone-pages.md`](docs/phone-pages.md) | The pages a guest's phone opens, and rebranding them per event (`config/web/`, `/preview`) |
| [`docs/screen-decoration.md`](docs/screen-decoration.md) | Dressing the kiosk idle screen per event — bunting, a graphic, a titled font (`config/decor/`) |
| [`docs/overlays.md`](docs/overlays.md) | Overlay compositing and the optional phone-side layout picker |
| [`docs/gpio.md`](docs/gpio.md) | The arcade button and "lighting ok" LED wiring and pins |
| [`docs/remote-start.md`](docs/remote-start.md) | Taking the photo from the phone's layout page, and getting the result back automatically |
| [`docs/i18n.md`](docs/i18n.md) | Guest-facing text: the `.properties` layers, adding a language |

[`CLAUDE.md`](CLAUDE.md) has the architecture overview and the data flow
through `PhotoSession.run()`.

## Running

```bash
./gradlew run
```

On the Pi itself, package a distributable and run it there instead of over a
remote X session, since JavaFX needs the local display:

```bash
./gradlew installDist
./build/install/photobooth/bin/photobooth
```

### On a dev machine (no Pi/GPIO/camera attached)

`camera.type=auto` (the default) and the GPIO controller both detect at
startup whether they're on Linux and fall back automatically on anything
else:

- **Camera**: a real webcam via [`imagesnap`](https://github.com/rharder/imagesnap)
  (`brew install imagesnap`) if installed, else a synthetic placeholder photo
  so the rest of the pipeline still runs. Force one with
  `camera.type=pi|webcam|placeholder`.
- **GPIO**: a logging no-op — click or tap the window to start a session
  instead of pressing an arcade button.

The Cloudflare tunnel still starts, so `cloudflared` needs to be on `PATH`
and the machine needs internet — see
[`docs/download-server-and-tunnel.md`](docs/download-server-and-tunnel.md).

## Testing

```bash
./gradlew test
```

## Status

This is a first scaffold: the pieces (GPIO, camera + self-view preview,
overlay compositing and phone-side overlay picker, QR generation, download
server, JavaFX kiosk UI, Cloudflare tunnel) are wired together and build
cleanly, but the UI styling and hardware-specific pin numbers / camera
settings still need to be tuned against the real booth hardware. The
Cloudflare quick tunnel path hasn't been exercised against real Pi/arm64
hardware from this repo.
