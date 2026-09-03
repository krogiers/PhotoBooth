# Known issues — bug backlog

Findings from the bug-hunter review (2026-08-30). Ordered by severity. Check
this list before re-running a bug hunt so items aren't re-reported. Move an
item to "Fixed" (with the date) when resolved.

---

## Open

Nothing open — all nine findings from the 2026-08-30 review are addressed
below. Re-run the bug hunter for a fresh pass.

---

## Fixed (2026-09-02)

### 1. An exception in `BoothView.show()` half-wired the booth
- **Was:** `previewSource.start()` (missing `rpicam-vid` / `ffmpeg`, or
  `camera.type=pi` off a Pi) threw out of `installBooth`; the idle screen was
  already live with a working tap handler but the `session.*` callbacks and
  `gpio`/close wiring never ran → first tap set `sessionInProgress=true`,
  nothing ever cleared it, booth dead until restart.
- **Fix:** `RpicamPreviewStream`/`FfmpegPreviewStream` `start()` now catch a
  failed `ProcessBuilder.start()` (log + run without a self-view, don't
  throw). `BoothView.show()` wires all `session.*` callbacks **before**
  touching the preview and calls `previewSource.start()` inside `runCatching`.

### 2. Streaming `stop()` broke the "camera released before return" contract
- **Was:** `destroyForcibly()` wasn't awaited, so `stop()` could return while
  `rpicam-vid` / `ffmpeg` still held the sensor → the very next
  `camera.capture()` failed and the shot was lost.
- **Fix:** both streams `waitFor(5s)` after `destroyForcibly()`.

### 3. No double-`start()` guard on the streaming previews; `dismissResult` not idempotent
- **Was:** a second `start()` with no `stop()` orphaned the first process
  (still holding the camera); `dismissResult` could run twice for one result
  (tap racing the auto-dismiss timer) → `restartPreview()` twice.
- **Fix:** `AtomicBoolean` CAS guard in both streams' `start()`/`stop()`
  (mirrors `PreviewLoop`). `dismissResult` now `compareAndSet`s the
  generation as it claims it, so the racing second call is rejected.

### 4. Success-path `sessionInProgress` reset depended entirely on `dismissResult`
- **Was:** a throw in `PhotoSession.run()`'s `finally` (`gpio.setLed(false)`)
  escaped *after* `onPhotoReady` had fired; and a throw inside `showResult`
  on the FX thread left `sessionInProgress` set forever ("busy").
- **Fix:** `run()`'s `finally` body is each wrapped in `runCatching`; the
  `onPhotoReady` handler wraps `showResult` and falls back to `abortToIdle()`
  on failure.

### 5. Scrim `Region` binding leak in `showResult`
- **Was:** a fresh `Region` bound to `root`'s width/height per result screen;
  `children.setAll` dropped the node but never unbound it → a listener (and
  the node) retained on `root` for every shoot.
- **Fix:** one reused `resultScrim` field with `maxWidth/maxHeight =
  Double.MAX_VALUE` (fills the `StackPane`, no bindings).

### 6. Teardown didn't contain its own failures; `build()` leaked on the tunnel-timeout path; double-close
- **Fix:** `PhotoBoothComponent.close()` wraps every step in `runCatching`
  and is idempotent (`AtomicBoolean` CAS). `build()` now has a try/catch from
  the server start onward that stops the tunnel / server / GPIO before
  rethrowing. `Main.kt` funnels every exit path (either window's close,
  `stop()`, a JVM shutdown hook) through one idempotent `teardown()`.

### 7. FX-thread synchronous image decode in `showResult` / `updateOverlayPreview`
- **Fix:** both now pass `backgroundLoading = true` to `Image(...)`.

### 8. Timestamp filenames collided silently
- **Fix:** `PhotoSession` formats `yyyyMMdd-HHmmss-SSS` (millisecond
  resolution) — two shoots in the same wall-clock second no longer overwrite
  each other's original + merged file.

### 9. Config number/enum parsing was fail-fast to the error screen
- **Was:** one typo in e.g. `decor.3.rotate` or `kiosk.qr.layout.position`
  threw → caught by `runCatching { AppConfig.load() }` → whole booth on the
  error screen.
- **Fix:** `DecorConfig.from` parses each layer in `runCatching` (skip the bad
  layer with a warning, keep the rest); title scalars use `toDoubleOrNull` /
  a caught position parse. `KioskLayout.from` falls each bad position back to
  that element's default with a warning. `DecorPosition.parse` itself stays
  strict (it's the low-level parser).
