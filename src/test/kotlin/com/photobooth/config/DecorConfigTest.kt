package com.photobooth.config

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecorConfigTest {

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply {
        pairs.forEach { (k, v) -> setProperty(k, v) }
    }

    @Test
    fun `empty properties produce the NONE decor`() {
        val decor = DecorConfig.from(Properties())

        assertNull(decor.buntingFile)
        assertNull(decor.graphicFile)
        assertNull(decor.titleText)
        assertNull(decor.titleFontFile)
        assertTrue(decor.isEmpty)
        assertEquals(DecorConfig.NONE, decor)
    }

    @Test
    fun `blank values are treated as unset`() {
        val decor = DecorConfig.from(props("decor.title.text" to "   ", "decor.bunting.file" to ""))

        assertNull(decor.titleText)
        assertNull(decor.buntingFile)
        assertTrue(decor.isEmpty)
    }

    @Test
    fun `file, number, colour and text values are read through`() {
        val decor = DecorConfig.from(
            props(
                "decor.bunting.file" to "config/decor/bunting.png",
                "decor.bunting.height" to "150",
                "decor.graphic.file" to "config/decor/sweden.png",
                "decor.graphic.height" to "300",
                "decor.title.text" to "Tibo's uitzwaai feestje",
                "decor.title.font" to "config/decor/title.ttf",
                "decor.title.size" to "72",
                "decor.title.color" to "#1f3b63",
            ),
        )

        assertEquals(File("config/decor/bunting.png"), decor.buntingFile)
        assertEquals(150.0, decor.buntingHeight)
        assertEquals(File("config/decor/sweden.png"), decor.graphicFile)
        assertEquals(300.0, decor.graphicHeight)
        assertEquals("Tibo's uitzwaai feestje", decor.titleText)
        assertEquals(File("config/decor/title.ttf"), decor.titleFontFile)
        assertEquals(72.0, decor.titleSize)
        assertEquals("#1f3b63", decor.titleColor)
        assertTrue(!decor.isEmpty)
    }

    @Test
    fun `positions parse case- and separator-insensitively`() {
        assertEquals(DecorPosition.TOP_CENTER, DecorPosition.parse("top-center"))
        assertEquals(DecorPosition.BOTTOM_RIGHT, DecorPosition.parse("BOTTOM_RIGHT"))
        assertEquals(DecorPosition.CENTER, DecorPosition.parse("  Center "))

        val decor = DecorConfig.from(props("decor.graphic.position" to "bottom-left", "decor.title.position" to "top-right"))
        assertEquals(DecorPosition.BOTTOM_LEFT, decor.graphicPosition)
        assertEquals(DecorPosition.TOP_RIGHT, decor.titlePosition)
    }

    @Test
    fun `an unknown position fails with the allowed values listed`() {
        val e = assertFailsWith<IllegalStateException> { DecorPosition.parse("middle-ish") }
        assertTrue(e.message!!.contains("top-left") && e.message!!.contains("bottom-center"), e.message)
    }

    @Test
    fun `defaults fill in for the numeric and position keys when only text is set`() {
        val decor = DecorConfig.from(props("decor.title.text" to "Party"))

        assertEquals(DecorConfig.NONE.titleSize, decor.titleSize)
        assertEquals(DecorConfig.NONE.graphicHeight, decor.graphicHeight)
        assertEquals(DecorConfig.NONE.titleColor, decor.titleColor)
        assertEquals(DecorConfig.NONE.graphicPosition, decor.graphicPosition)
        assertEquals(DecorConfig.NONE.titlePosition, decor.titlePosition)
    }
}
