package com.photobooth.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhonePagesTest {

    private fun emptyWebDir(): File = Files.createTempDirectory("photobooth-web").toFile()

    @Test
    fun `renders the bundled photo page with the model values`() {
        val html = PhonePages(emptyWebDir()).render(
            "photo.mustache",
            mapOf(
                "title" to "Your photo",
                "photoUrl" to "/photos/abc.png",
                "downloadUrl" to "/download/abc.png",
                "downloadLabel" to "Download",
            ),
        )

        assertTrue(html.contains("""src="/photos/abc.png""""), html)
        assertTrue(html.contains("""href="/download/abc.png""""), html)
        assertTrue(html.contains(">Download<"), html)
        assertTrue(html.contains("""<link rel="stylesheet" href="/web/style.css">"""), html)
        assertFalse(html.contains("btn secondary"), "no 'another photo' button without againUrl")
    }

    @Test
    fun `the photo page shows the 'another photo' button only when againUrl is set`() {
        val html = PhonePages(emptyWebDir()).render(
            "photo.mustache",
            mapOf(
                "title" to "Your photo",
                "photoUrl" to "/photos/abc.png",
                "downloadUrl" to "/download/abc.png",
                "downloadLabel" to "Download",
                "againUrl" to "/choose",
                "againLabel" to "Another photo",
            ),
        )

        assertTrue(html.contains("""<a class="btn secondary" href="/choose">Another photo</a>"""), html)
    }

    @Test
    fun `renders one tile per overlay on the choose page, marking the selected one`() {
        val html = PhonePages(emptyWebDir()).render(
            "choose.mustache",
            mapOf(
                "heading" to "Pick one",
                "overlays" to listOf(
                    mapOf("label" to "Frame", "thumbUrl" to "/overlays/frame.png", "chooseUrl" to "/choose/frame.png", "selected" to false),
                    mapOf("label" to "Party", "thumbUrl" to "/overlays/party.png", "chooseUrl" to "/choose/party.png", "selected" to true),
                ),
            ),
        )

        assertEquals(2, Regex("""class="tile""").findAll(html).count(), html)
        assertEquals(1, Regex("""class="tile selected"""").findAll(html).count(), html)
        assertTrue(html.contains("/choose/frame.png") && html.contains("/choose/party.png"), html)
        assertTrue(html.contains(">Party<"), html)
    }

    @Test
    fun `the choose page shows the take-photo button only when canShoot is set`() {
        val model = mapOf(
            "heading" to "Pick one",
            "overlays" to emptyList<Map<String, Any?>>(),
            "shootUrl" to "/shoot",
            "shootLabel" to "Take the photo",
        )
        val pages = PhonePages(emptyWebDir())

        assertFalse(pages.render("choose.mustache", model).contains("btn shoot"))
        val withButton = pages.render("choose.mustache", model + ("canShoot" to true))
        assertTrue(withButton.contains("""href="/shoot""""), withButton)
        assertTrue(withButton.contains(">Take the photo<"), withButton)
    }

    @Test
    fun `the message page emits a meta refresh only when asked`() {
        val pages = PhonePages(emptyWebDir())
        assertFalse(pages.render("message.mustache", mapOf("heading" to "Wait")).contains("http-equiv"))
        assertTrue(
            pages.render("message.mustache", mapOf("heading" to "Wait", "metaRefresh" to "2; url=/shoot/wait"))
                .contains("""<meta http-equiv="refresh" content="2; url=/shoot/wait">"""),
        )
    }

    @Test
    fun `HTML-escapes model values`() {
        val html = PhonePages(emptyWebDir()).render(
            "message.mustache",
            mapOf("heading" to "Tom & Jerry <3"),
        )

        assertTrue(html.contains("Tom &amp; Jerry &lt;3"), html)
        assertFalse(html.contains("Tom & Jerry <3"), html)
    }

    @Test
    fun `a config web template overrides the bundled one`() {
        val webDir = emptyWebDir()
        File(webDir, "message.mustache").writeText("OVERRIDE: {{heading}}")

        val html = PhonePages(webDir).render("message.mustache", mapOf("heading" to "hello"))

        assertEquals("OVERRIDE: hello", html)
    }

    @Test
    fun `the brand bar shows only when a name or logo is configured`() {
        val webDir = emptyWebDir()

        val plain = PhonePages(webDir, brandName = null).render("message.mustache", mapOf("heading" to "hi"))
        assertFalse(plain.contains("brandbar"), plain)

        val branded = PhonePages(webDir, brandName = "ACME Events").render("message.mustache", mapOf("heading" to "hi"))
        assertTrue(branded.contains("brandbar"), branded)
        assertTrue(branded.contains("ACME Events"), branded)
    }

    @Test
    fun `asset serves the bundled stylesheet, a config web override, and nothing else`() {
        val webDir = emptyWebDir()
        val pages = PhonePages(webDir)

        val bundled = pages.asset("style.css")
        assertNotNull(bundled)
        assertEquals("text/css; charset=utf-8", bundled.contentType)
        assertTrue(String(bundled.bytes).contains("--accent"))

        File(webDir, "style.css").writeText("body{background:hotpink}")
        assertEquals("body{background:hotpink}", String(pages.asset("style.css")!!.bytes))

        assertNull(pages.asset("../photobooth.properties"))
        assertNull(pages.asset("logo.exe"))
        assertNull(pages.asset("does-not-exist.png"))
    }
}
