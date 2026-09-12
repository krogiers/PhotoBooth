package com.photobooth.config

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecorConfigTest {

    // Stands in for an event bundle dir — relative decor.<n>.file values resolve under it.
    private val base = File("/tmp/pb-event-decor")

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply {
        pairs.forEach { (k, v) -> setProperty(k, v) }
    }

    private fun decor(vararg pairs: Pair<String, String>) = DecorConfig.from(props(*pairs), base)

    @Test
    fun `empty properties produce the NONE decor`() {
        val decor = DecorConfig.from(Properties(), base)

        assertTrue(decor.items.isEmpty())
        assertNull(decor.titleText)
        assertTrue(decor.isEmpty)
        assertEquals(DecorConfig.NONE, decor)
    }

    @Test
    fun `a numbered image layer is read with position, size and rotation`() {
        val decor = decor(
            "decor.1.file" to "decor/wimpels.png",
            "decor.1.position" to "top-left",
            "decor.1.width" to "45%",
            "decor.1.rotate" to "-12",
        )

        assertEquals(1, decor.items.size)
        val item = decor.items.single()
        assertEquals(File(base, "decor/wimpels.png"), item.file)
        assertEquals(DecorPosition.TOP_LEFT, item.position)
        assertEquals(DecorSize(0.45, percentOfScreen = true), item.width)
        assertNull(item.height)
        assertEquals(-12.0, item.rotate)
        assertTrue(!decor.isEmpty)
    }

    @Test
    fun `an absolute decor path is left alone`() {
        val item = decor("decor.1.file" to "/etc/photobooth/shared.png").items.single()
        assertEquals(File("/etc/photobooth/shared.png"), item.file)
    }

    @Test
    fun `a layer with a bad key is skipped, the good layers survive`() {
        val decor = decor(
            "decor.1.file" to "decor/a.png",
            "decor.1.position" to "top-left",
            "decor.2.file" to "decor/b.png",
            "decor.2.rotate" to "sideways",          // not a number
            "decor.3.file" to "decor/c.png",
            "decor.3.position" to "middle-ish",       // not a position
            "decor.4.file" to "decor/d.png",
            "decor.4.position" to "bottom-right",
        )

        assertEquals(
            listOf(File(base, "decor/a.png"), File(base, "decor/d.png")),
            decor.items.map { it.file },
        )
    }

    @Test
    fun `a bad title position falls back to the default, no throw`() {
        val decor = decor("decor.title.text" to "Hi", "decor.title.position" to "nope")
        assertEquals(DecorConfig.NONE.titlePosition, decor.titlePosition)
    }

    @Test
    fun `layers are collected in order and gaps are skipped`() {
        val decor = decor(
            "decor.1.file" to "a.png",
            "decor.3.file" to "c.png", // decor.2 absent
            "decor.5.file" to "e.png",
        )

        assertEquals(listOf("a.png", "c.png", "e.png"), decor.items.map { it.file.name })
    }

    @Test
    fun `a layer with no position defaults to centre and no size means natural`() {
        val item = decor("decor.1.file" to "x.png").items.single()

        assertEquals(DecorPosition.CENTER, item.position)
        assertNull(item.width)
        assertNull(item.height)
        assertEquals(0.0, item.rotate)
    }

    @Test
    fun `DecorSize parses px and percent`() {
        assertEquals(DecorSize(800.0, percentOfScreen = false), DecorSize.parse("800"))
        assertEquals(DecorSize(0.9, percentOfScreen = true), DecorSize.parse(" 90% "))
        assertNull(DecorSize.parse(""))
        assertNull(DecorSize.parse(null))
    }

    @Test
    fun `the text title is still read from decor_title keys`() {
        val decor = decor(
            "decor.title.text" to "Tibo's uitzwaai feestje",
            "decor.title.font" to "decor/title.ttf",
            "decor.title.size" to "72",
            "decor.title.color" to "#1f3b63",
            "decor.title.position" to "top-center",
        )

        assertEquals("Tibo's uitzwaai feestje", decor.titleText)
        assertEquals(File(base, "decor/title.ttf"), decor.titleFontFile)
        assertEquals(72.0, decor.titleSize)
        assertEquals("#1f3b63", decor.titleColor)
        assertEquals(DecorPosition.TOP_CENTER, decor.titlePosition)
        assertTrue(!decor.isEmpty)
    }

    @Test
    fun `positions parse case- and separator-insensitively`() {
        assertEquals(DecorPosition.TOP_CENTER, DecorPosition.parse("top-center"))
        assertEquals(DecorPosition.BOTTOM_RIGHT, DecorPosition.parse("BOTTOM_RIGHT"))
        assertEquals(DecorPosition.CENTER, DecorPosition.parse("  Center "))
        assertEquals(DecorPosition.CENTER_RIGHT, DecorPosition.parse("center-right"))
    }

    @Test
    fun `an unknown position fails with the allowed values listed`() {
        val e = assertFailsWith<IllegalStateException> { DecorPosition.parse("middle-ish") }
        assertTrue(e.message!!.contains("top-left") && e.message!!.contains("bottom-center"), e.message)
    }
}
