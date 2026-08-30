package com.photobooth.session

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlaySelectionTest {

    private fun setUp(): Pair<OverlaySelection, File> {
        val overlaysDir = Files.createTempDirectory("photobooth-overlays").toFile()
        File(overlaysDir, "frame.png").writeText("frame")
        File(overlaysDir, "hearts.png").writeText("hearts")
        val defaultOverlay = File(overlaysDir, "frame.png")
        return OverlaySelection(overlaysDir, defaultOverlay) to overlaysDir
    }

    @Test
    fun `defaults to the fallback overlay until something is chosen`() {
        val (selection, overlaysDir) = setUp()
        assertEquals(File(overlaysDir, "frame.png"), selection.current())
    }

    @Test
    fun `choosing a listed overlay makes it current until reset`() {
        val (selection, overlaysDir) = setUp()

        assertTrue(selection.choose("hearts.png"))
        assertEquals(File(overlaysDir, "hearts.png"), selection.current())

        selection.reset()
        assertEquals(File(overlaysDir, "frame.png"), selection.current())
    }

    @Test
    fun `choosing a file outside the overlays dir is rejected`() {
        val (selection, _) = setUp()

        assertFalse(selection.choose("../../etc/passwd"))
        assertFalse(selection.choose("does-not-exist.png"))
    }

    @Test
    fun `available lists only the png files in the overlays dir`() {
        val (selection, overlaysDir) = setUp()
        File(overlaysDir, "readme.txt").writeText("not an overlay")

        assertEquals(listOf("frame.png", "hearts.png"), selection.available().map { it.name })
    }

    @Test
    fun `onChanged fires with the new current overlay on a successful choose`() {
        val (selection, overlaysDir) = setUp()
        val changes = mutableListOf<File>()
        selection.onChanged = { changes.add(it) }

        selection.choose("hearts.png")

        assertEquals(listOf(File(overlaysDir, "hearts.png")), changes)
    }

    @Test
    fun `onChanged does not fire for a rejected choose`() {
        val (selection, _) = setUp()
        val changes = mutableListOf<File>()
        selection.onChanged = { changes.add(it) }

        selection.choose("does-not-exist.png")

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `onChanged fires with the default overlay on reset, but only if something was chosen`() {
        val (selection, overlaysDir) = setUp()
        val changes = mutableListOf<File>()

        selection.reset() // nothing chosen yet — no-op, no event
        selection.onChanged = { changes.add(it) }
        selection.choose("hearts.png")
        selection.reset()

        assertEquals(listOf(File(overlaysDir, "hearts.png"), File(overlaysDir, "frame.png")), changes)
    }
}
