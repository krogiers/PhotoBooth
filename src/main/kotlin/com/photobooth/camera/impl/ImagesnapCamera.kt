package com.photobooth.camera.impl

import com.photobooth.camera.Camera
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Captures a still photo from a Mac's built-in/USB webcam via `imagesnap`
 * (`brew install imagesnap`). Handy for exercising the full countdown ->
 * capture -> overlay -> QR pipeline on a MacBook during development, without
 * any Pi hardware attached.
 */
class ImagesnapCamera(
    private val timeoutSeconds: Long = 10,
    /** `-d "<name>"` when set — which camera to use (see `imagesnap -l`). */
    private val device: String? = null,
) : Camera {

    override fun capture(outputFile: File): File {
        outputFile.parentFile?.mkdirs()

        // -w gives the camera a moment to warm up/auto-expose before the shot.
        val command = buildList {
            add("imagesnap"); add("-w"); add("1")
            device?.let { add("-d"); add(it) }
            add(outputFile.absolutePath)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // Don't leave imagesnap holding the webcam open.
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        }
        if (!finished) {
            process.destroyForcibly()
            error("imagesnap timed out after ${timeoutSeconds}s")
        }
        if (process.exitValue() != 0) {
            val output = process.inputStream.bufferedReader().readText()
            error("imagesnap failed (exit ${process.exitValue()}): $output")
        }
        check(outputFile.exists()) {
            "imagesnap reported success but no file was written to ${outputFile.absolutePath}"
        }
        return outputFile
    }

    companion object {
        /** Whether the `imagesnap` binary is on PATH. */
        fun isAvailable(): Boolean = try {
            val process = ProcessBuilder("which", "imagesnap").start()
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }
}
