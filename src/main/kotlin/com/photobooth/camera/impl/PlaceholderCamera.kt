package com.photobooth.camera.impl

import com.photobooth.camera.Camera
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Fallback [Camera] used when neither the Pi camera stack nor a real webcam
 * is available (e.g. a dev machine with no camera at all, or CI). Draws a
 * synthetic photo so the rest of the pipeline — overlay merge, QR code,
 * download — can still be exercised end to end.
 */
class PlaceholderCamera(
    private val width: Int = 1920,
    private val height: Int = 1080,
) : Camera {

    override fun capture(outputFile: File): File {
        outputFile.parentFile?.mkdirs()

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color.getHSBColor(Random.nextFloat(), 0.45f, 0.85f)
            graphics.fillRect(0, 0, width, height)

            graphics.color = Color(0, 0, 0, 140)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, height / 10)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            graphics.drawString("No camera attached", width / 12, height / 2)
            graphics.drawString(timestamp, width / 12, height / 2 + height / 8)
        } finally {
            graphics.dispose()
        }

        ImageIO.write(image, "jpg", outputFile)
        return outputFile
    }
}
