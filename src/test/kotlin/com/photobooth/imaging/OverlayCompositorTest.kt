package com.photobooth.imaging

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayCompositorTest {

    @Test
    fun `merges overlay on top of the photo and preserves photo dimensions`() {
        val tempDir = Files.createTempDirectory("photobooth-test").toFile()

        val photo = File(tempDir, "photo.png").apply {
            ImageIO.write(solidImage(200, 100, Color.BLUE), "png", this)
        }
        val overlay = File(tempDir, "overlay.png").apply {
            ImageIO.write(solidImage(200, 100, Color(255, 0, 0, 128)), "png", this)
        }
        val output = File(tempDir, "merged.png")

        OverlayCompositor().merge(photo, overlay, output)

        assertTrue(output.exists())
        val merged = ImageIO.read(output)
        assertEquals(200, merged.width)
        assertEquals(100, merged.height)
    }

    private fun solidImage(width: Int, height: Int, color: Color): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return image
    }
}
