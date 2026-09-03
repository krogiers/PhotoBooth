package com.photobooth.camera.impl

import com.photobooth.camera.PreviewSource
import com.photobooth.util.daemonPool
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous preview via `rpicam-vid`'s MJPEG output, piped straight to
 * stdout — the same Bookworm `libcamera` stack [LibcameraCamera] uses for
 * the actual shutter capture. Not verified against real Pi hardware from
 * this repo; smoke-test on the actual booth before relying on it.
 */
class RpicamPreviewStream(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val frameRate: Int = 15,
    /** `--camera <n>` when set — match the shutter camera's `camera.device`. */
    private val cameraIndex: Int? = null,
) : PreviewSource {
    override var onFrame: (ByteArray) -> Unit = {}

    private val log = LoggerFactory.getLogger(RpicamPreviewStream::class.java)
    // A start()/stop() cycle happens every shoot; this guards against a second start()
    // with no stop() in between, which would orphan the first rpicam-vid (and its two
    // reader threads) still holding the camera.
    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var io: ExecutorService? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("start() called while already running — ignoring (stop() first)")
            return
        }
        val proc = try {
            ProcessBuilder(
                buildList {
                    add("rpicam-vid")
                    add("--codec"); add("mjpeg")
                    add("--inline")
                    add("--timeout"); add("0")
                    add("--width"); add(width.toString())
                    add("--height"); add(height.toString())
                    add("--framerate"); add(frameRate.toString())
                    add("--nopreview")
                    cameraIndex?.let { add("--camera"); add(it.toString()) }
                    add("-o"); add("-")
                },
            ).redirectErrorStream(false).start()
        } catch (e: Exception) {
            // Missing binary / wrong OS: the booth must still run, just without a self-view.
            running.set(false)
            log.warn("Couldn't start rpicam-vid ({}) — no live self-view", e.message)
            return
        }
        process = proc

        // One reader per pipe: draining stderr keeps the process from blocking on a full pipe.
        val pool = daemonPool("rpicam-preview", 2)
        io = pool
        pool.execute { runCatching { proc.errorStream.use { it.copyTo(OutputStream.nullOutputStream()) } } }
        pool.execute { runCatching { MjpegFrameReader.read(BufferedInputStream(proc.inputStream), onFrame) } }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        process?.let { proc ->
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                // Don't return until the sensor is actually released — the real capture
                // runs right after this on the same task (PreviewSource.stop's contract).
                proc.waitFor(5, TimeUnit.SECONDS)
            }
        }
        io?.shutdownNow()
        process = null
        io = null
    }
}
