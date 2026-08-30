package com.photobooth.i18n

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StringsTest {

    private val keys = listOf(
        "starting", "startup_failed", "tap_to_start", "smile", "scan_choose_overlay",
        "scan_download", "server_running", "choose_overlay_page_title", "choose_overlay_heading",
        "overlay_not_found", "take_photo_button", "another_photo", "shoot_busy",
        "photo_incoming", "photo_incoming_hint", "photo_incoming_failed", "photo_page_title",
        "download_button", "no_photo_yet", "scan_last_photo",
    )

    private fun emptyOverridesDir(): File = Files.createTempDirectory("photobooth-i18n").toFile()

    @Test
    fun `every bundled language has a translation for every key`() {
        for (code in listOf("en", "nl")) {
            val strings = Strings.load(code, emptyOverridesDir())
            for (key in keys) {
                assertTrue(strings.t(key).isNotBlank(), "'$code' is missing a translation for '$key'")
            }
        }
    }

    @Test
    fun `formats positional arguments into the translated template`() {
        val overridesDir = emptyOverridesDir()
        File(overridesDir, "en.properties").writeText("scan_download=Hi %s, scan %s to download\n")

        assertEquals(
            "Hi Ann, scan now to download",
            Strings.load("en", overridesDir).t("scan_download", "Ann", "now"),
        )
    }

    @Test
    fun `an unknown language with no override file fails clearly instead of silently falling back`() {
        assertFailsWith<IllegalStateException> { Strings.load("fr", emptyOverridesDir()) }
    }

    @Test
    fun `a config override file can replace just one word of a bundled language`() {
        val overridesDir = emptyOverridesDir()
        File(overridesDir, "en.properties").writeText("tap_to_start=Touch here to begin\n")

        val strings = Strings.load("en", overridesDir)

        assertEquals("Touch here to begin", strings.t("tap_to_start"))
        // Every other key still comes from the bundled English defaults, untouched.
        assertEquals("Smile!", strings.t("smile"))
    }

    @Test
    fun `an override file alone can define a whole new, unbundled language`() {
        val overridesDir = emptyOverridesDir()
        File(overridesDir, "fr.properties").writeText("tap_to_start=Touchez pour commencer\n")

        val strings = Strings.load("fr", overridesDir)

        assertEquals("Touchez pour commencer", strings.t("tap_to_start"))
        // Keys the override doesn't define fall back to English rather than crashing.
        assertEquals("Smile!", strings.t("smile"))
    }
}
