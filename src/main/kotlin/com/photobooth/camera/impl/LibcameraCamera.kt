package com.photobooth.camera.impl

import com.photobooth.camera.Camera
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Captures a still photo using the Raspberry Pi camera module, by shelling
 * out to the `rpicam-still` CLI (Raspberry Pi OS Bookworm and newer).
 *
 * On older Raspberry Pi OS releases (Bullseye and earlier) pass
 * `binary = "libcamera-still"` instead. For a USB webcam, implement [Camera]
 * with a different backend (e.g. v4l2 via a Java webcam library) instead of
 * this class.
 */
class LibcameraCamera(
    private val binary: String = "rpicam-still",
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val timeoutSeconds: Long = 10,
    /** `--camera <n>` when set — which attached camera to use (see `rpicam-still --list-cameras`). */
    private val cameraIndex: Int? = null,
) : Camera {

    override fun capture(outputFile: File): File {
        outputFile.parentFile?.mkdirs()

        val command = buildList {
            add(binary)
            add("--output"); add(outputFile.absolutePath)
            add("--width"); add(width.toString())
            add("--height"); add(height.toString())
            add("--timeout"); add("1000")
            add("--nopreview")
            cameraIndex?.let { add("--camera"); add(it.toString()) }
        }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // Don't leave the capture subprocess holding the camera device open.
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        }
        if (!finished) {
            process.destroyForcibly()
            error("Camera capture timed out after ${timeoutSeconds}s")
        }
        if (process.exitValue() != 0) {
            val output = process.inputStream.bufferedReader().readText()
            error("Camera capture failed (exit ${process.exitValue()}): $output")
        }
        check(outputFile.exists()) {
            "Camera reported success but no file was written to ${outputFile.absolutePath}"
        }
        return outputFile
    }
}
