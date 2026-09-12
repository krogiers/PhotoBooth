package com.photobooth.server.admin

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSchemaTest {

    @Test
    fun `int spec accepts blank and whole numbers, rejects the rest`() {
        val spec = SettingsSchema.BY_KEY.getValue("countdown.seconds")
        assertNull(spec.validate(""))
        assertNull(spec.validate(" 5 "))
        assertNotNull(spec.validate("5.5"))
        assertNotNull(spec.validate("later"))
    }

    @Test
    fun `enum spec is case-insensitive and lists the allowed values on error`() {
        val spec = SettingsSchema.BY_KEY.getValue("kiosk.qr.layout.position")
        assertNull(spec.validate("BOTTOM-LEFT"))
        val err = spec.validate("middle")
        assertNotNull(err)
        assertTrue("bottom-left" in err)
    }

    @Test
    fun `bool spec only takes true or false`() {
        val spec = SettingsSchema.BY_KEY.getValue("secondscreen.enabled")
        assertNull(spec.validate("true"))
        assertNull(spec.validate("False"))
        assertNotNull(spec.validate("yes"))
    }

    @Test
    fun `timing and idle-screen keys are live, hardware and ports need a restart`() {
        fun live(key: String) = SettingsSchema.BY_KEY.getValue(key).live
        assertTrue(live("countdown.seconds") && live("smile.hold.ms") && live("kiosk.qr.layout.position"))
        assertTrue(!live("server.port") && !live("gpio.button.pin") && !live("camera.type"))
    }

    @Test
    fun `admin password is the only secret`() {
        assertEquals(listOf("admin.password"), SettingsSchema.SPECS.filter { it.secret }.map { it.key })
    }
}

class SettingsFileTest {

    private fun tmp() = File(Files.createTempDirectory("pb-settings").toFile(), "photobooth.properties")

    @Test
    fun `write rewrites a present key in place and leaves everything else`() {
        val f = tmp()
        f.writeText(
            """
            # header comment
            countdown.seconds=3

            decor.1.file=decor/flags.png
            server.port=8080
            """.trimIndent(),
        )

        SettingsFile.write(f, mapOf("countdown.seconds" to "6"))
        val text = f.readText()

        assertTrue("# header comment" in text)
        assertTrue("countdown.seconds=6" in text)
        assertTrue("countdown.seconds=3" !in text)
        assertTrue("decor.1.file=decor/flags.png" in text, "unmanaged key preserved")
        assertTrue("server.port=8080" in text)
    }

    @Test
    fun `write appends a brand-new key under a marker`() {
        val f = tmp()
        f.writeText("countdown.seconds=3\n")

        SettingsFile.write(f, mapOf("smile.hold.ms" to "2000"))
        val text = f.readText()

        assertTrue("countdown.seconds=3" in text)
        assertTrue("added via the admin panel" in text)
        assertTrue("smile.hold.ms=2000" in text)
    }

    @Test
    fun `write creates the file when it does not exist`() {
        val f = tmp()
        assertTrue(!f.exists())

        SettingsFile.write(f, mapOf("server.port" to "9000"))

        assertEquals("9000", SettingsFile.read(f)["server.port"])
    }

    @Test
    fun `read ignores comments and blanks`() {
        val f = tmp()
        f.writeText("# c\n\n  ui.language = nl \n")
        assertEquals(mapOf("ui.language" to "nl"), SettingsFile.read(f))
    }
}
