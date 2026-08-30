package com.photobooth.imaging

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Merges a captured photo with a transparent PNG overlay (frame, logo,
 * event branding) and writes the result out as a new file. The original
 * capture passed in as [sourcePhoto] is never modified. Stateless — which
 * overlay to use is passed in per call, since [com.photobooth.session.OverlaySelection]
 * lets that vary from one shoot to the next.
 */
class OverlayCompositor {

    fun merge(sourcePhoto: File, overlayFile: File, outputFile: File): File {
        val base = ImageIO.read(sourcePhoto) ?: error("Could not read photo: ${sourcePhoto.absolutePath}")
        val overlay = ImageIO.read(overlayFile) ?: error("Could not read overlay: ${overlayFile.absolutePath}")

        val result = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(base, 0, 0, null)
            // Overlay is stretched to the photo's resolution so a single
            // template can be authored once regardless of camera resolution.
            graphics.drawImage(overlay, 0, 0, base.width, base.height, null)
        } finally {
            graphics.dispose()
        }

        outputFile.parentFile?.mkdirs()
        ImageIO.write(result, "png", outputFile)
        return outputFile
    }
}
