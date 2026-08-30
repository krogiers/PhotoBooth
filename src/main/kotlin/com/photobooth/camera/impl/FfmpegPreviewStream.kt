package com.photobooth.camera.impl

import com.photobooth.camera.PreviewSource
import com.photobooth.util.daemonPool
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Continuous webcam preview via `ffmpeg`'s `avfoundation` input, piped
 * straight out as a raw MJPEG stream — used on macOS during development so
 * the self-view is real smooth-ish video rather than one still per second.
 * The Pi's equivalent is [RpicamPreviewStream]; `ffmpeg` isn't a fallback
 * for it (avfoundation is macOS-only).
 */
class FfmpegPreviewStream(
    private val deviceIndex: Int = 0,
    private val frameRate: Int = 10,
    private val width: Int = 1280,
    private val height: Int = 720,
) : PreviewSource {
    override var onFrame: (ByteArray) -> Unit = {}

    private var process: Process? = null
    private var io: ExecutorService? = null

    override fun start() {
        val proc = ProcessBuilder(
            "ffmpeg",
            "-f", "avfoundation",
            "-framerate", frameRate.toString(),
            "-video_size", "${width}x$height",
            "-i", deviceIndex.toString(),
            "-vf", "fps=$frameRate",
            "-f", "mjpeg",
            "-q:v", "5",
            "-",
        ).redirectErrorStream(false).start()
        process = proc

        // One reader per pipe: ffmpeg logs progress/warnings to stderr, so drain it too or the
        // process eventually blocks on a full pipe.
        val pool = daemonPool("ffmpeg-preview", 2)
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

    companion object {
        /** Whether the `ffmpeg` binary is on PATH. */
        fun isAvailable(): Boolean = try {
            val p = ProcessBuilder("which", "ffmpeg").start()
            p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
