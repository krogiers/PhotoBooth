package com.photobooth.camera.impl

import com.photobooth.camera.PreviewSource
import com.photobooth.util.daemonPool
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

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

    private var process: Process? = null
    private var io: ExecutorService? = null

    override fun start() {
        val proc = ProcessBuilder(
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
        process = proc

        // One reader per pipe: draining stderr keeps the process from blocking on a full pipe.
        val pool = daemonPool("rpicam-preview", 2)
        io = pool
        pool.execute { runCatching { proc.errorStream.use { it.copyTo(OutputStream.nullOutputStream()) } } }
        pool.execute { runCatching { MjpegFrameReader.read(BufferedInputStream(proc.inputStream), onFrame) } }
    }

    override fun stop() {
        process?.let { proc ->
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        io?.shutdownNow()
        process = null
        io = null
    }
}
