package com.photobooth.camera.impl

import com.photobooth.camera.Camera
import com.photobooth.camera.PreviewSource
import com.photobooth.util.daemonScheduler
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fallback [PreviewSource] that repeatedly grabs a still from [camera], used
 * when neither [FfmpegPreviewStream] nor [RpicamPreviewStream] is available
 * (no `ffmpeg`/`rpicam-vid`, or [PlaceholderCamera]). This is a periodic-still
 * self-view, not smooth live video — [intervalMillis] is how often a new
 * still is grabbed, not a frame rate. Wrap [camera] in [SynchronizedCamera]
 * first if it's also used for the real shutter capture elsewhere.
 */
class PreviewLoop(
    private val camera: Camera,
    private val intervalMillis: Long,
    private val previewFile: File,
) : PreviewSource {
    override var onFrame: (ByteArray) -> Unit = {}

    private val running = AtomicBoolean(false)
    private var scheduler: ScheduledExecutorService? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val s = daemonScheduler("camera-preview-loop")
        scheduler = s
        s.scheduleWithFixedDelay(::grabFrame, 0, intervalMillis, TimeUnit.MILLISECONDS)
    }

    private fun grabFrame() {
        try {
            camera.capture(previewFile)
            onFrame(previewFile.readBytes())
        } catch (e: Exception) {
            // A single missed preview frame isn't worth stopping the loop for.
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        val s = scheduler ?: return
        scheduler = null
        // PreviewSource.stop() must not return until the camera is free: shutdown() schedules no
        // further grabs, then awaitTermination waits out the one already in flight rather than
        // racing the real shutter capture that follows.
        s.shutdown()
        if (!s.awaitTermination(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) s.shutdownNow()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 15_000L
    }
}
