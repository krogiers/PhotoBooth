# Known issues — bug backlog

Findings from the bug-hunter review (2026-08-30). Not yet fixed. Ordered by
severity. Check this list before re-running a bug hunt so items aren't
re-reported. Mark `[x]` and move to a "Fixed" section (with the commit) when
resolved.

---

## Booth-bricking — fix first

### 1. An exception in `BoothView.show()` half-wires the booth
- [ ] **open**
- **Where:** `Main.kt` `installBooth` (no `runCatching`); `ui/BoothView.kt` `show()` — `stage.scene.root = root` and `root.setOnMouseClicked{…}` run *before* `previewSource?.start()`, which runs *before* the `session.onCountdownTick / onBeforeCapture / onPhotoReady` assignments and `Main.kt`'s `gpio.onButtonPressed{…}` + `stage.setOnCloseRequest{…}`.
- **Mechanism:** `RpicamPreviewStream.start()` / `FfmpegPreviewStream.start()` do `ProcessBuilder(...).start()` which throws `IOException` when the binary is missing (`libcamera-vid` on Bullseye vs hardcoded `rpicam-vid`) or `camera.type=pi` is set on a non-Pi. The throw escapes the `Platform.runLater` runnable → logged only. The idle screen is already live with a working tap handler, but the session callbacks stay no-ops and gpio/close are never wired.
- **Trigger:** misconfigured / wrong-OS camera stack, at startup.
- **Impact:** first tap sets `sessionInProgress=true`, `onPhotoReady` is a no-op, nothing clears the flag → every later trigger says "busy". Dead until restart, and restart doesn't fix the misconfig.
- **Fix:** wrap the `ProcessBuilder(...).start()` in `RpicamPreviewStream`/`FfmpegPreviewStream` in `runCatching` (log, leave `process` null → black self-view but app fine). Also set the session callbacks + gpio/close wiring *before* `previewSource?.start()`, and/or wrap `installBooth` so a failure lands on the error screen.

### 3. No double-`start()` guard on the streaming preview sources; `dismissResult` not idempotent
- [ ] **open**
- **Where:** `camera/impl/RpicamPreviewStream.kt` / `FfmpegPreviewStream.kt` `start()` (no re-entrancy guard); `ui/BoothView.kt` `dismissResult()` (the `resultGeneration` guard only rejects *superseded* results, not a second dismissal of the current one).
- **Mechanism:** a second `start()` without an intervening `stop()` overwrites `process` and `io`; the first `rpicam-vid` + its two reader threads are orphaned and keep the camera → new stream gets immediate EOF (black self-view) and the next `camera.capture()` fails. `PreviewLoop` is immune (has an `AtomicBoolean` CAS). `dismissResult` can run twice for the same generation (tap-dismiss vs the already-running timer task whose `cancel(false)` loses), each calling `restartPreview()`.
- **Trigger:** result-screen tap racing the auto-dismiss timer; also the interleaving in finding 4.
- **Impact:** orphan camera-holding process; booth needs restart.
- **Fix:** add an `AtomicBoolean` CAS guard to the streaming sources' `start()` (mirror `PreviewLoop`). Bump `resultGeneration` (or set a `dismissed` flag) at the top of `dismissResult`.

### 6. Teardown doesn't contain its own failures; `build()` leaks on the tunnel-timeout path; double-close
- [ ] **open**
- **Where:** `PhotoBoothComponent.close()` (only `previewSource.stop()` in `runCatching`); `PhotoBoothComponent.build()` (starts Netty + creates the Pi4J context *before* `tunnel.start()`); `Main.kt` — `component` is never nulled, so `setOnCloseRequest{ c.close() }` then `Application.stop(){ component?.close() }` both run.
- **Mechanism:**
  - If `downloadServer.stop()` or `tunnel.stop()` throws, `gpio.close()` is skipped → GPIO/LED left claimed; on the `Application.stop()` path the following `bootstrap.shutdownNow()` is skipped too.
  - `tunnel.start()` throws on a ~20 s timeout; no `PhotoBoothComponent` is constructed, so nothing closes the already-bound Netty port or the Pi4J context.
  - Second `Pi4jGpioController.close()` → `pi4j.shutdown()` on an already-shut context throws, aborting the rest of `stop()`.
- **Impact:** leaked port / GPIO; usually reclaimed on JVM exit, but blocks a clean in-process restart.
- **Fix:** wrap each step of `close()` in `runCatching`; give `build()` a try/catch that closes what it already created on any failure; null `component` after close (or guard against a second close).

---

## Real bugs — should fix

### 2. `RpicamPreviewStream.stop()` / `FfmpegPreviewStream.stop()` break the "camera released before return" contract
- [ ] **open**
- **Where:** `camera/impl/RpicamPreviewStream.kt` `stop()` (≈:47-55), identical in `FfmpegPreviewStream.kt`.
- **Mechanism:** `proc.destroy()` → `waitFor(3s)` → `destroyForcibly()` **not awaited**, then `io?.shutdownNow()`. `PreviewSource.stop()` is documented "must not return until the device is released" and runs on the capture `CompletableFuture` right before `camera.capture()`. If `rpicam-vid` doesn't exit within 3 s of SIGTERM (busy Pi, I/O stall), `stop()` returns while the sensor is still being released.
- **Impact:** `rpicam-still` then fails to acquire the camera → capture exception → `abortToIdle()`. Shot lost. Self-recovering next shoot.
- **Fix:** `waitFor` after `destroyForcibly()`, like `PreviewLoop.stop()` does with `awaitTermination`.

### 4. Success-path `sessionInProgress` reset depends entirely on `dismissResult`
- [ ] **open**
- **Where:** `session/PhotoSession.kt` `run()` — `onPhotoReady(...)` fires *before* the `finally { gpio.setLed(false); overlaySelection?.reset() }`. `ui/BoothView.kt` — no `try/finally` around the success path equivalent to `requestStart`'s failure `catch`.
- **Mechanism:** if `gpio.setLed(false)` throws in the `finally`, `session.run()` throws *after* `showResult` was already queued → FX thread runs `showResult` (schedules `pendingDismiss`, doesn't touch `sessionInProgress`) then `abortToIdle` (hides the result the guest needs, `restartPreview` #1); 15 s later `pendingDismiss` fires `dismissResult(sameGen)` → passes the guard → `restartPreview` #2. Separately, if `showResult` itself throws on the FX thread, nothing clears `sessionInProgress` → permanent "busy".
- **Fix:** wrap the `PhotoSession.run()` `finally` body in `runCatching` (LED-off / reset must not be able to fail a completed session). Add a safety net that clears `sessionInProgress` if `showResult` throws.

### 5. Scrim `Region` binding leak in `showResult`
- [ ] **open**
- **Where:** `ui/BoothView.kt` `showResult()` — `Region().apply { styleClass += "result-scrim" }.also { it.prefWidthProperty().bind(root.widthProperty()); it.prefHeightProperty().bind(root.heightProperty()) }`.
- **Mechanism:** a fresh bound `Region` per result screen. `children.setAll` drops the old node but never `unbind`s it, so `root`'s width/height properties retain a listener (and the Region) for every shoot. Unbounded growth over a long unattended event.
- **Fix:** the binding is unnecessary — a `StackPane` child fills by default (or set `maxWidth/maxHeight = Double.MAX_VALUE`). Reuse one scrim node, or drop the bindings.

---

## Polish — optional

### 7. FX-thread synchronous image decode in `showResult` / `updateOverlayPreview`
- [ ] **open**
- **Where:** `ui/BoothView.kt` — `ImageView(Image(merged.toURI().toString()))` and `Image(overlayFile.toURI().toString())`.
- **Impact:** decoding a full-res (≈1920×1080) PNG freshly written to the SD card, on the FX thread, stalls the UI ~tens–150 ms on a Pi when the result screen appears.
- **Fix:** `Image(url, /* backgroundLoading = */ true)`.

### 8. Timestamp filenames collide silently
- [ ] **open**
- **Where:** `session/PhotoSession.kt` — `yyyyMMdd-HHmmss` (1 s resolution), no uniqueness check; `OverlayCompositor.merge` / `camera.capture` overwrite.
- **Impact:** with `countdown.seconds=0` and a fast camera, two shoots in the same wall-clock second overwrite the first group's original + merged file. `/shoot/wait` compares `lastPhotoFileName` as a string → unchanged → polls until the 25 s timeout, shows "photo incoming failed" though the photo exists.
- **Fix:** millisecond resolution (`yyyyMMdd-HHmmss-SSS`) or a uniqueness suffix.

### 9. Config number/enum parsing is fail-fast to the error screen
- [ ] **open**
- **Where:** `config/AppConfig.kt` (`.toInt()/.toLong()/.toDouble()`), `config/DecorConfig.kt` (`str("decor.$n.rotate")?.toDouble()`, `DecorPosition.parse` → `error(...)`), `config/KioskLayout.kt`.
- **Impact:** a single typo in e.g. `decor.3.rotate` throws → caught by `runCatching { AppConfig.load() }` → whole booth on the error screen. Contrast the missing-decor-*image* path, which correctly logs and skips.
- **Fix:** per-layer decor parse in `runCatching` → skip the bad layer with a warning; consider a `warn + default` for the scalar keys too.
