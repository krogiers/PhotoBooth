# Overlays

## The need

Every capture is merged with a transparent PNG — a frame, a logo, event
branding. Optionally, each group picks *which* overlay on their own phone
before posing.

## Compositing — [`OverlayCompositor`](../src/main/kotlin/com/photobooth/imaging/OverlayCompositor.kt)

`merge(sourcePhoto, overlayFile, outputFile)` draws the overlay on top of the
photo, stretched to the photo's resolution (so one template works regardless
of camera resolution), and writes a new PNG. The original capture is never
modified — it's kept at `data/<date>/originals/<timestamp>.jpg` alongside the
merged `data/<date>/merged/<timestamp>.png` (the `<date>` folder keeps
back-to-back events apart — see `data.subdir`).

`overlay.file` (default [`config/overlay.png`](../config/overlay.png)) is the
overlay used for every shoot unless the picker below is on and a guest chose
another.

## Phone-side picker — [`OverlaySelection`](../src/main/kotlin/com/photobooth/session/OverlaySelection.kt)

Set `overlay.selection.enabled=true` and drop one `.png` per option into
`overlays.dir` (default [`config/overlays/`](../config/overlays/); a few
samples are included). The idle screen then shows a QR, "scan to choose your
layout", linking to `/choose` on the
[download server](download-server-and-tunnel.md):

- `/choose` lists every `.png` in `overlays.dir` as tap targets, the current
  pick outlined, and — when `remote_start.enabled=true` — a **📸 Take the
  photo** button (see [`remote-start.md`](remote-start.md)).
- `/choose/{name}` records the pick and redirects back to `/choose` (now
  showing it selected). The name must resolve to a file directly inside
  `overlays.dir` — a `../` escape is rejected, since this is reachable from
  any phone.
- `/overlays/{name}` serves the thumbnail images.

There is **one** active choice at a time, matching the one-group-at-a-time
reality of a single physical booth — it is not per-guest and not meant for
several booths sharing a server. The pick applies to the *next* shoot, then
`reset()` clears it in `PhotoSession`'s `finally` so the following group
starts from `overlay.file` again.

An `onChanged` callback fires whenever the active overlay changes — a phone
pick, or the post-shoot reset — and the kiosk uses it to composite the
current overlay live onto the self-view, so guests see exactly what their
photo will look like before the shutter fires.

## Config

- `overlay.file` — the default overlay PNG.
- `overlay.selection.enabled` — `true` turns on the phone-side picker.
- `overlays.dir` — folder of selectable `.png` options.
