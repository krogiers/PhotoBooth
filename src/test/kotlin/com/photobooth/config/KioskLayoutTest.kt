package com.photobooth.config

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KioskLayoutTest {

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply {
        pairs.forEach { (k, v) -> setProperty(k, v) }
    }

    @Test
    fun `empty properties give the default layout, both QRs on`() {
        val layout = KioskLayout.from(Properties())

        assertEquals(KioskLayout.DEFAULT, layout)
        assertEquals(DecorPosition.CENTER_LEFT, layout.layoutQr)
        assertEquals(DecorPosition.CENTER_RIGHT, layout.recoveryQr)
        assertEquals(DecorPosition.BOTTOM_CENTER, layout.tapHint)
        assertTrue(layout.layoutQrEnabled && layout.recoveryQrEnabled)
    }

    @Test
    fun `each QR can be switched off independently`() {
        val layout = KioskLayout.from(
            props("kiosk.qr.layout.enabled" to "false", "kiosk.qr.recovery.enabled" to "true"),
        )

        assertEquals(false, layout.layoutQrEnabled)
        assertEquals(true, layout.recoveryQrEnabled)
    }

    @Test
    fun `each element's position is read from its key`() {
        val layout = KioskLayout.from(
            props(
                "kiosk.qr.layout.position" to "top-left",
                "kiosk.qr.recovery.position" to "bottom-right",
                "kiosk.taphint.position" to "top-center",
            ),
        )

        assertEquals(DecorPosition.TOP_LEFT, layout.layoutQr)
        assertEquals(DecorPosition.BOTTOM_RIGHT, layout.recoveryQr)
        assertEquals(DecorPosition.TOP_CENTER, layout.tapHint)
    }

    @Test
    fun `blank value falls back to the default for that element`() {
        val layout = KioskLayout.from(props("kiosk.qr.layout.position" to "   "))
        assertEquals(KioskLayout.DEFAULT.layoutQr, layout.layoutQr)
    }

    @Test
    fun `center-left and center-right parse`() {
        assertEquals(DecorPosition.CENTER_LEFT, DecorPosition.parse("center-left"))
        assertEquals(DecorPosition.CENTER_RIGHT, DecorPosition.parse("CENTER_RIGHT"))
    }

    @Test
    fun `an unknown position is rejected`() {
        assertFailsWith<IllegalStateException> {
            KioskLayout.from(props("kiosk.taphint.position" to "middle"))
        }
    }
}
