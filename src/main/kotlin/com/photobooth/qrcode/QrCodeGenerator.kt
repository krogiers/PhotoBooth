package com.photobooth.qrcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream

/**
 * Renders a QR code for a URL as PNG bytes. QR codes are transient screen
 * content — shown once on the result screen, or fixed on the idle screen —
 * and every one encodes the current run's Cloudflare tunnel host, so they're
 * worthless after a restart. Nothing is written to disk.
 */
class QrCodeGenerator(private val size: Int = 300) {

    fun pngBytes(content: String): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return ByteArrayOutputStream().also { MatrixToImageWriter.writeToStream(matrix, "PNG", it) }.toByteArray()
    }
}
