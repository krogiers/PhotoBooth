package com.photobooth.camera.impl

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class MjpegFrameReaderTest {

    @Test
    fun `splits back-to-back JPEG frames on their SOI-EOI markers`() {
        val frame1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val frame2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 8, 0xFF.toByte(), 0xD9.toByte())
        val stream = ByteArrayInputStream(frame1 + frame2)

        val frames = mutableListOf<ByteArray>()
        MjpegFrameReader.read(stream) { frames.add(it) }

        assertEquals(2, frames.size)
        assertEquals(frame1.toList(), frames[0].toList())
        assertEquals(frame2.toList(), frames[1].toList())
    }

    @Test
    fun `ignores bytes before the first frame's SOI marker`() {
        val garbage = byteArrayOf(0, 1, 2)
        val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 5, 0xFF.toByte(), 0xD9.toByte())
        val stream = ByteArrayInputStream(garbage + frame)

        val frames = mutableListOf<ByteArray>()
        MjpegFrameReader.read(stream) { frames.add(it) }

        assertEquals(1, frames.size)
        assertEquals(frame.toList(), frames[0].toList())
    }

    @Test
    fun `an incomplete trailing frame with no EOI is dropped, not emitted`() {
        val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 5, 0xFF.toByte(), 0xD9.toByte())
        val incomplete = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val stream = ByteArrayInputStream(frame + incomplete)

        val frames = mutableListOf<ByteArray>()
        MjpegFrameReader.read(stream) { frames.add(it) }

        assertEquals(1, frames.size)
    }
}
