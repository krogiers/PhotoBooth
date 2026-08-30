package com.photobooth.camera.impl

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Splits a raw stream of back-to-back JPEG frames — as emitted by
 * `ffmpeg -f mjpeg -` or `rpicam-vid --codec mjpeg -o -`, piped straight to
 * a process's stdout with no multipart/HTTP framing around them — back into
 * individual frames, by scanning for each JPEG's SOI (0xFFD8) and EOI
 * (0xFFD9) markers.
 */
object MjpegFrameReader {
    private const val SOI_2 = 0xD8
    private const val EOI_1 = 0xFF
    private const val EOI_2 = 0xD9
    private const val MARKER = 0xFF

    /** Blocks reading from [input] until it's exhausted (the process exits) or an error occurs, calling [onFrame] per frame. Wrap [input] in a `BufferedInputStream` first. */
    fun read(input: InputStream, onFrame: (ByteArray) -> Unit) {
        val buffer = ByteArrayOutputStream()
        var inFrame = false
        var prev = -1
        var current = input.read()
        while (current != -1) {
            if (!inFrame) {
                if (prev == MARKER && current == SOI_2) {
                    inFrame = true
                    buffer.reset()
                    buffer.write(MARKER)
                    buffer.write(SOI_2)
                }
            } else {
                buffer.write(current)
                if (prev == EOI_1 && current == EOI_2) {
                    inFrame = false
                    onFrame(buffer.toByteArray())
                }
            }
            prev = current
            current = input.read()
        }
    }
}
