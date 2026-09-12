# Events & per-event settings

Every setting is **per event**. An event is a self-contained folder under
`events/`; `events/_active` (a one-line file) names the one the booth loads
at startup.

```
events/
  _active                       ← one line: the active event's id
  default/
    event.properties            ← admin metadata: name, date, notes
    photobooth.properties       ← every AppConfig key (pins, countdown, port, …)
    overlay.png                 ← the default overlay merged onto every photo
    overlays/                   ← selectable overlays (overlay.selection.enabled)
    decor/                      ← idle-screen decor images + title font
    i18n/                       ← guest-facing text: nl.properties, en.properties, …
    web/                        ← phone-page templates + style.css + logo
    kiosk/kiosk.css             ← all kiosk-screen styling
    data/<date>/originals|merged ← this event's photos
  2026-10-11-jansen/            ← another event, prepared in advance
    …
```

## How settings resolve

1. Built-in defaults in `src/main/resources/` — always the fallback.
2. `events/<active>/photobooth.properties` — overrides those, key by key.
3. The override folders (`i18n/`, `web/`, `kiosk/kiosk.css`, `overlays/`,
   `decor/`) layer on top of the bundled defaults the same way.

Path-valued keys (`overlay.file`, `overlays.dir`, `data.dir`,
`decor.<n>.file`, `decor.title.font`) are resolved **relative to the event
folder** — `overlay.file=overlay.png` means `events/<active>/overlay.png`. An
absolute path is left as-is (handy for a shared asset on the Pi).

## Choosing the active event

`events/_active` holds the id. Change it and restart the booth. The admin
panel (below) does this with a button so you can prepare events ahead of time
and switch on the day.

To load a specific bundle **without touching `_active`**, set the
`PHOTOBOOTH_EVENT` env var (or the `photobooth.event` system property):

```bash
PHOTOBOOTH_EVENT=dev build/install/photobooth/bin/photobooth
```

If it names a bundle that doesn't exist, the booth logs a warning and falls
back to `_active`.

## Running: the real booth vs. dev

Two bundles ship:

| Bundle | For | Run it with |
| --- | --- | --- |
| `events/default/` | the real booth — Raspberry Pi, full-screen, second operator screen | `./gradlew run` |
| `events/dev/` | a Mac / laptop — windowed, no GPIO, webcam or placeholder camera, admin panel on | `./gradlew run -Pdev` |

`-Pdev` sets `PHOTOBOOTH_EVENT=dev` **and** `PHOTOBOOTH_WINDOWED=1`. The dev
bundle uses its own `server.port` (8090) and `admin.port` (8082), so it can
run at the same time as the real one. Other combinations:

```bash
./gradlew run -Pevent=spring-fair   # a specific bundle
./gradlew run -Pwindowed            # the active event, but in a window
```

On the Pi's `installDist` binary, use the env var directly:
`PHOTOBOOTH_EVENT=default build/install/photobooth/bin/photobooth`.

## Upgrading from the old flat `config/`

On first run, if `events/` doesn't exist yet and a legacy `config/` does, the
booth **copies** `config/photobooth.properties` + the `overlay.png` /
`overlays/` / `decor/` / `i18n/` / `web/` / `kiosk/` folders into
`events/default/`, rewrites the `config/…` path prefixes inside the copied
properties, and points `events/_active` at `default`. The originals are left
in `config/` untouched — once you've confirmed the booth still runs, delete
`config/` (keep `config/photobooth.properties.example`, it's the reference).

## `./gradlew seedConfig`

Writes the bundled `i18n/` / `web/` / `kiosk/kiosk.css` defaults into
`events/default/` as an editable starting point. Existing files are left
alone, so it's safe to re-run.

---

## Admin panel

A web panel on the booth lets an **admin** (a new role) change this event's
settings graphically — no editing files by hand.

### Turn it on

In the active event's `photobooth.properties`:

```properties
admin.enabled=true
admin.port=8081          # its own port — see below
admin.password=choose-something
```

Then open `http://<the-booth-machine-ip>:8081/` from any device on the same
network and log in as user **`admin`** with that password.

It runs on **its own port**, separate from `server.port`. Only `server.port`
is fronted by the Cloudflare tunnel, so the admin panel is reachable on the
local network only — never from the public internet. It stays off unless
`admin.enabled=true` *and* `admin.password` is non-blank; a failure to bind
`admin.port` is logged and the booth carries on without the panel.

### What's there now

- **Dashboard** — active event, ports, key toggles.
- **Events** — all bundles under `events/`, which one is active.
- **Settings** — a **typed, validated form** for this event's
  `photobooth.properties`. Only the fields you change are written; comments,
  key order and the `decor.<n>.*` layer block are left untouched. The
  password field is never rendered and stays as-is when left blank.
- **Text** — the merged guest-facing strings.
- **Overlays** — a gallery of the bundle's overlay PNGs. **Upload** (drop in
  one or more `.png` files — validated as real PNGs, ≤ 20 MB, filenames
  sanitised into `overlays/`), **delete**, and **set as default** (writes
  `overlay.file=overlays/<name>` and reloads the kiosk so the self-view
  preview updates at once). Renaming for gallery order isn't in the panel yet
  — the list is alphabetical, so prefix names like `01_`, `02_` to order them.

### Save = live where it can be

Each setting is tagged **`live`** or **`restart`**:

- **`live`** (the countdown/result timings, every idle-screen key and the
  default overlay — `countdown.seconds`, `smile.hold.ms`,
  `result.display.seconds`, `kiosk.qr.*`, `kiosk.taphint.position`,
  `decor.title.*`, `overlay.file`): applied the moment you save. The kiosk UI
  is rebuilt in place from the new file — the camera, GPIO, download server
  and tunnel keep running.
- **`restart`** (hardware and wiring — `gpio.*`, `camera.*`, `server.port`,
  `admin.*`, `screen.*`, `secondscreen.enabled`, `overlay.selection.enabled`,
  `overlays.dir`, `ui.language`, branding, storage): written to the file, but
  the panel tells you to restart the booth for them to take effect.

### Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 1 | Per-event bundle layout + load path + legacy migration | **done** |
| 2 | Admin server, password auth (LAN only, never via the tunnel), read-only views | **done** |
| 3 | Typed settings editor with validation + "reload booth UI" for live keys | **done** |
| 4 | Overlays: upload, delete, set default | **done** (reorder deferred) |
| 5 | i18n text editor (per key / per language, add a language) + decor-layer editor | planned |
| 6 | Kiosk / CSS editor with live preview; create / clone / switch / schedule events | planned |
