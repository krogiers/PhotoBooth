# The optional second screen (operator picker)

## The need

At a staffed booth an operator often stands beside the person posing. Giving
them their own touchscreen — the layout gallery and one big button to fire
the shutter — is faster than walking around to the arcade button and keeps
the booth screen clean for the guest.

Off by default; the booth runs on one screen as before.

## What it is

A second full-screen window on a second display, showing:

- the **layout gallery** — the same `overlays.dir` thumbnails as the phone
  `/choose` page; tap one to pick it (the booth self-view updates live),
- a big red **"take the photo"** button at the bottom.

Differences from the phone `/choose` page: no download, and **no photo is
shown afterwards** — after a shot the picker just stays as it is. While a
shot is running the button is disabled, so a second one can't be started
until the first finishes (the same `sessionInProgress` guard the arcade
button and phone use).

It's a native JavaFX window ([`PickerView`](../src/main/kotlin/com/photobooth/ui/PickerView.kt)),
not a browser — styled by `.picker-*` in `kiosk/kiosk.css` (override in
`config/kiosk/kiosk.css`), including the red of the button.

## Config

```properties
secondscreen.enabled=false   # true turns the picker screen on
screen.booth.display=0       # display index for the main booth screen
screen.picker.display=1      # display index for the picker screen
```

Display `0` is the first display (usually the primary). Rules:

- **Booth screen** — if `screen.booth.display` isn't present, the booth still
  runs, on the primary display, with a warning. It must run somewhere.
- **Picker screen** — appears **only** on `screen.picker.display`. If that
  display isn't present, there is simply no picker screen (no fallback to the
  primary — "no screen, no scene"). Same if `overlay.selection.enabled=false`
  (it would have no gallery).

Each window has its own kiosk flag — `screen.booth.fullscreen` /
`screen.picker.fullscreen` (both default on) — so one can be full-screen
while the other is a plain window. Full-screen is real
`Stage.isFullScreen`, applied after the window is shown on its target
display so it covers that display edge to edge. JavaFX lets only one Stage
hold it at a time, so bringing the picker up can drop the booth out of
full-screen — `Main.kt` re-asserts the booth's flag right after. The
`PHOTOBOOTH_WINDOWED` env var still forces both windowed for a dev run.
When windowed, size is `window.width/height` or, blank, ~80% of the display.

**Dev on one monitor:** run windowed (`PHOTOBOOTH_WINDOWED=1` or
`screen.picker.fullscreen=false`) with `secondscreen.enabled=true`. If
`screen.picker.display` isn't a real display the picker then opens as a
second window on the primary, offset from the booth window, so it can be
tested without a second monitor. A full-screen kiosk never does this — a
missing display means no picker.

**Shutting down:** closing either window (or `stop()` / a `Ctrl-C` on
`./gradlew run`) quits the whole app — one idempotent teardown closes both
windows and stops the preview, download server, tunnel and GPIO. You never
have to close the two windows separately.
