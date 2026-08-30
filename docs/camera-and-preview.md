# Camera capture and the live self-view

Two separate concerns share one physical camera: the **shutter capture**
(one still per shoot) and the **self-view preview** (a continuous feed on the
idle/countdown screens). They can't hold the device at the same time, so the
preview is always stopped before a capture and restarted after.

The package `com.photobooth.camera` holds just the two interfaces — `Camera`
and `PreviewSource`; every concrete backend lives under
`com.photobooth.camera.impl`.

## Shutter capture — [`Camera`](../src/main/kotlin/com/photobooth/camera/Camera.kt)

One method: `capture(outputFile: File): File`. Implementations:

| `camera.type` | Class | How |
| --- | --- | --- |
| `pi` | [`LibcameraCamera`](../src/main/kotlin/com/photobooth/camera/impl/LibcameraCamera.kt) | Shells out to `rpicam-still` (Raspberry Pi OS Bookworm's `libcamera` stack). Pass `binary = "libcamera-still"` for Bullseye and earlier. |
| `webcam` | [`ImagesnapCamera`](../src/main/kotlin/com/photobooth/camera/impl/ImagesnapCamera.kt) | `imagesnap` on macOS (`brew install imagesnap`). For exercising the pipeline on a laptop. |
| `placeholder` | [`PlaceholderCamera`](../src/main/kotlin/com/photobooth/camera/impl/PlaceholderCamera.kt) | Draws a synthetic image so overlay merge / QR / download still run with no camera at all. |
| `auto` (default) | — | Pi camera on Linux; otherwise `imagesnap` if it's on `PATH`; otherwise the placeholder. |

The choice is made once in
[`PhotoBoothComponent`](../src/main/kotlin/com/photobooth/PhotoBoothComponent.kt)'s
`selectCamera`. Every real capture goes through
[`SynchronizedCamera`](../src/main/kotlin/com/photobooth/camera/impl/SynchronizedCamera.kt),
which serializes calls so a `PreviewLoop` still and the real shutter never
hit a single-shot CLI at once. A capture subprocess that overruns its
timeout — or whose thread is interrupted — is force-killed so it can't keep
the device open.

## Self-view preview — [`PreviewSource`](../src/main/kotlin/com/photobooth/camera/PreviewSource.kt)

`onFrame` (raw JPEG bytes), `start()`, `stop()`. The contract: **`stop()`
must not return until the camera device is released.**

| Source | When `selectPreviewSource` picks it | How |
| --- | --- | --- |
| [`RpicamPreviewStream`](../src/main/kotlin/com/photobooth/camera/impl/RpicamPreviewStream.kt) | The Pi Camera is in use — `camera.type=pi`, or `auto` on Linux | `rpicam-vid --codec mjpeg -o -`, a real continuous stream |
| [`FfmpegPreviewStream`](../src/main/kotlin/com/photobooth/camera/impl/FfmpegPreviewStream.kt) | Not Linux and `ffmpeg` is on `PATH` (`brew install ffmpeg`) | `ffmpeg -f avfoundation ... -f mjpeg -`, a real continuous stream |
| [`PreviewLoop`](../src/main/kotlin/com/photobooth/camera/impl/PreviewLoop.kt) | Anything else — a forced `placeholder`/`webcam` camera, or macOS without `ffmpeg` | Grabs one still every `preview.interval.ms` via the `Camera` on a `ScheduledExecutorService`. Noticeably choppier; `imagesnap`/`rpicam-still` are single-shot, not a stream. `stop()` shuts the scheduler down and awaits the in-flight grab, so the camera is free on return. |

Both streams emit back-to-back JPEG frames with no HTTP framing;
[`MjpegFrameReader`](../src/main/kotlin/com/photobooth/camera/impl/MjpegFrameReader.kt)
splits them on the `FFD8`/`FFD9` markers.

## The capture race

[`PhotoSession.run()`](../src/main/kotlin/com/photobooth/session/PhotoSession.kt)
runs the capture on a `CompletableFuture` **alongside** the countdown loop,
so capture latency hides behind the seconds the guest is already watching
tick down. That task first calls `onBeforeCapture()` — wired in `BoothView`
to `previewSource.stop()` — and only then `camera.capture()`. Because
`stop()` blocks until the device is free, the two never overlap. If `stop()`
or the capture throws, `capture.join()` rethrows it as the session's error,
the UI returns to idle, and the preview is restarted (see `BoothView`).

## Config

- `camera.type` — `auto` | `pi` | `webcam` | `placeholder`.
- `preview.interval.ms` — only used by the `PreviewLoop` fallback; how often
  it grabs a still. Lower feels more "live", more camera load. Irrelevant
  once a real stream is available.
