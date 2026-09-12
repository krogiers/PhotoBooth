package com.photobooth.config

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventStoreTest {

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toFile()

    private fun store(root: File, legacy: File = tempDir("pb-nolegacy")) = EventStore(root, legacy)

    @Test
    fun `active() with nothing set creates and points at events default`() {
        val root = tempDir("pb-events")
        val bundle = store(root).active()

        assertEquals("default", bundle.id)
        assertTrue(File(root, "default").isDirectory)
        assertEquals("default", File(root, "_active").readText().trim())
    }

    private fun withEventProperty(value: String?, block: () -> Unit) {
        val key = "photobooth.event"
        val prev = System.getProperty(key)
        try {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            block()
        } finally {
            if (prev == null) System.clearProperty(key) else System.setProperty(key, prev)
        }
    }

    @Test
    fun `the photobooth_event property forces a bundle over _active`() {
        val root = tempDir("pb-events")
        File(root, "dev").mkdirs()
        File(root, "default").mkdirs()
        File(root, "_active").writeText("default\n")

        withEventProperty("dev") {
            assertEquals("dev", store(root).active().id)
        }
    }

    @Test
    fun `an unknown forced event falls back to _active`() {
        val root = tempDir("pb-events")
        File(root, "real").mkdirs()
        File(root, "_active").writeText("real\n")

        withEventProperty("ghost") {
            assertEquals("real", store(root).active().id)
        }
    }

    @Test
    fun `active() honours the _active pointer`() {
        val root = tempDir("pb-events")
        File(root, "spring-fair").mkdirs()
        File(root, "_active").writeText("spring-fair\n")

        assertEquals("spring-fair", store(root).active().id)
    }

    @Test
    fun `active() falls back to default when _active names a missing bundle`() {
        val root = tempDir("pb-events")
        File(root, "_active").writeText("does-not-exist\n")

        assertEquals("default", store(root).active().id)
    }

    @Test
    fun `list() skips underscore-prefixed entries and sorts by id`() {
        val root = tempDir("pb-events")
        listOf("b-event", "a-event", "_trash").forEach { File(root, it).mkdirs() }
        File(root, "_active").writeText("a-event\n")

        assertEquals(listOf("a-event", "b-event"), store(root).list().map { it.id })
    }

    @Test
    fun `setActive rejects an unknown id and accepts a real one`() {
        val root = tempDir("pb-events")
        File(root, "real").mkdirs()
        val s = store(root)

        assertFailsWith<IllegalArgumentException> { s.setActive("ghost") }
        s.setActive("real")
        assertEquals("real", File(root, "_active").readText().trim())
    }

    @Test
    fun `first run migrates a legacy flat config into events default`() {
        val root = tempDir("pb-events").also { it.delete() } // not created yet
        val legacy = tempDir("pb-legacy")
        File(legacy, "photobooth.properties").writeText(
            "overlay.file=config/overlay.png\ndecor.1.file=config/decor/wimpels.png\n",
        )
        File(legacy, "decor").mkdirs()
        File(legacy, "decor/wimpels.png").writeText("png")
        File(legacy, "i18n").mkdirs()
        File(legacy, "i18n/nl.properties").writeText("smile=Lachen")

        val bundle = EventStore(root, legacy).active()

        assertEquals("default", bundle.id)
        val props = File(root, "default/photobooth.properties").readText()
        assertTrue("overlay.file=overlay.png" in props, props)
        assertTrue("decor.1.file=decor/wimpels.png" in props, props)
        assertTrue(File(root, "default/decor/wimpels.png").isFile)
        assertTrue(File(root, "default/i18n/nl.properties").isFile)
        assertTrue(File(legacy, "photobooth.properties").isFile, "originals are left in place")
    }

    @Test
    fun `migration is skipped once events default has its own photobooth_properties`() {
        val root = tempDir("pb-events")
        File(root, "default").mkdirs()
        File(root, "default/photobooth.properties").writeText("countdown.seconds=5")
        val legacy = tempDir("pb-legacy")
        File(legacy, "photobooth.properties").writeText("countdown.seconds=9")

        EventStore(root, legacy).active()

        assertEquals("countdown.seconds=5", File(root, "default/photobooth.properties").readText())
    }

    @Test
    fun `migration still runs if seedConfig pre-created events default folders but no properties`() {
        val root = tempDir("pb-events")
        File(root, "default/i18n").mkdirs() // as `./gradlew seedConfig` would
        val legacy = tempDir("pb-legacy")
        File(legacy, "photobooth.properties").writeText("overlay.file=config/overlay.png")

        EventStore(root, legacy).active()

        assertTrue("overlay.file=overlay.png" in File(root, "default/photobooth.properties").readText())
    }
}
