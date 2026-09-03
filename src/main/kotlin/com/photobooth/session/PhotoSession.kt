package com.photobooth.session

import com.photobooth.camera.Camera
import com.photobooth.config.AppConfig
import com.photobooth.gpio.Gpio
import com.photobooth.imaging.OverlayCompositor
import com.photobooth.qrcode.QrCodeGenerator
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Orchestrates a single photo shoot end to end: countdown, "lighting ok" LED,
 * capture, keep the original, merge the overlay (the guest's own pick from
 * [OverlaySelection], if enabled), then generate a QR code for the download
 * link. Has no knowledge of JavaFX — call [run] from a background thread and
 * observe [onCountdownTick] / [onPhotoReady] to drive the UI.
 */
class PhotoSession(
    private val config: AppConfig,
    private val camera: Camera,
    private val gpio: Gpio,
    private val compositor: OverlayCompositor,
    private val qrCodeGenerator: QrCodeGenerator,
    /** Builds the public URL a guest's phone should fetch a merged photo from, given its file name — see how it's wired to the Cloudflare tunnel in `Main.kt`. */
    private val downloadUrlProvider: (fileName: String) -> String,
    /** Null when `overlay.selection.enabled=false` — [config]'s single [com.photobooth.config.AppConfig.overlayFile] is used for every shoot instead. */
    private val overlaySelection: OverlaySelection? = null,
) {
    var onCountdownTick: (secondsLeft: Int) -> Unit = {}
    var onPhotoReady: (merged: File, qrPng: ByteArray, downloadUrl: String) -> Unit = { _, _, _ -> }

    /**
     * Fires as soon as a countdown starts, on the background task that runs
     * alongside the visible countdown (see [run]) — not after it. A live
     * self-view backed by real camera hardware
     * ([com.photobooth.camera.impl.FfmpegPreviewStream], [com.photobooth.camera.impl.RpicamPreviewStream])
     * holds that device continuously, so [com.photobooth.ui.BoothView] uses
     * this to stop it first, freeing the device for the real capture that
     * runs right after on that same task. Must block until the device is free.
     */
    var onBeforeCapture: () -> Unit = {}

    fun run() {
        gpio.setLed(true)
        try {
            // Millisecond resolution: two shoots in the same wall-clock second (countdown.seconds=0,
            // a fast camera) would otherwise overwrite the first group's files.
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            val overlayFile = overlaySelection?.current() ?: config.overlayFile
            val originalFile = File(config.originalsDir, "$timestamp.jpg")

            // Stopping the live preview (the stream process fully releasing the camera) and then
            // running the real capture (device open, auto-exposure settling) run on a separate
            // task, the whole time alongside the countdown loop below — so that latency is hidden
            // behind the seconds the guest is already watching count down, instead of stacking on
            // top after "Smile!". The countdown itself keeps ticking at its own steady pace
            // regardless. onBeforeCapture is inside the task so a preview that fails to release
            // the camera surfaces as the capture failure below, not a silent missing file.
            val capture = CompletableFuture.runAsync {
                onBeforeCapture()
                camera.capture(originalFile)
            }

            for (secondsLeft in config.countdownSeconds downTo 1) {
                onCountdownTick(secondsLeft)
                Thread.sleep(1000)
            }
            // secondsLeft never reaches 0 in the loop above, so without this the UI would sit
            // frozen on "1" instead of showing "Smile!".
            onCountdownTick(0)
            val smileShownAt = System.currentTimeMillis()

            try {
                capture.join()
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }

            // Keep "Smile!" up for at least smile.hold.ms — the capture usually finishes during
            // the countdown, so without this it can flash by before the result screen appears.
            val remainingSmile = config.smileHoldMillis - (System.currentTimeMillis() - smileShownAt)
            if (remainingSmile > 0) Thread.sleep(remainingSmile)

            val merged = compositor.merge(originalFile, overlayFile, File(config.mergedDir, "$timestamp.png"))
            val downloadUrl = downloadUrlProvider(merged.name)
            val qrPng = qrCodeGenerator.pngBytes(downloadUrl)

            onPhotoReady(merged, qrPng, downloadUrl)
        } finally {
            // Neither of these may fail a shoot that already produced a photo — a throw here
            // would escape run() *after* onPhotoReady already fired, leaving the UI wedged.
            runCatching { gpio.setLed(false) }
            // Each group picks fresh — don't let this choice carry over to whoever steps up next.
            runCatching { overlaySelection?.reset() }
        }
    }
}
