package com.photobooth.qrcode

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrCodeGeneratorTest {

    @Test
    fun `pngBytes produces a PNG that decodes back to the original content`() {
        val content = "https://sample-words.trycloudflare.com/p/20260830-014043.png"

        val png = QrCodeGenerator().pngBytes(content)

        assertTrue(png.size >= 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte(), "not a PNG")
        assertEquals(content, decode(png))
    }

    private fun decode(png: ByteArray): String {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
        return MultiFormatReader().decode(bitmap).text
    }
}
