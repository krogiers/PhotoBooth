package com.photobooth.config

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {

    private fun propsFile(vararg lines: String): File {
        val dir = Files.createTempDirectory("photobooth-config").toFile()
        return File(dir, "photobooth.properties").apply { writeText(lines.joinToString("\n")) }
    }

    @Test
    fun `photos go under a per-session subfolder named for the date`() {
        val data = Files.createTempDirectory("photobooth-data").toFile()
        val config = AppConfig.load(propsFile("data.dir=${data.path}"), LocalDate.of(2026, 8, 30))

        assertEquals(File(data, "2026-08-30"), config.sessionDir)
        assertEquals(File(data, "2026-08-30/originals"), config.originalsDir)
        assertEquals(File(data, "2026-08-30/merged"), config.mergedDir)
        assertTrue(config.originalsDir.isDirectory && config.mergedDir.isDirectory, "subfolders should be created")
    }

    @Test
    fun `second screen is off by default with sensible display indices`() {
        val data = Files.createTempDirectory("photobooth-data").toFile()
        val config = AppConfig.load(propsFile("data.dir=${data.path}"), LocalDate.of(2026, 8, 30))

        assertEquals(false, config.secondScreenEnabled)
        assertEquals(0, config.boothDisplay)
        assertEquals(1, config.pickerDisplay)

        val on = AppConfig.load(
            propsFile(
                "data.dir=${data.path}",
                "secondscreen.enabled=true",
                "screen.booth.display=1",
                "screen.picker.display=2",
            ),
            LocalDate.of(2026, 8, 30),
        )
        assertEquals(true, on.secondScreenEnabled)
        assertEquals(1, on.boothDisplay)
        assertEquals(2, on.pickerDisplay)
    }

    @Test
    fun `window fullscreen defaults on and can be turned off`() {
        val data = Files.createTempDirectory("photobooth-data").toFile()
        assertEquals(true, AppConfig.load(propsFile("data.dir=${data.path}"), LocalDate.of(2026, 8, 30)).windowFullscreen)

        val off = AppConfig.load(
            propsFile("data.dir=${data.path}", "window.fullscreen=false"),
            LocalDate.of(2026, 8, 30),
        )
        assertEquals(false, off.windowFullscreen)
    }

    @Test
    fun `data_subdir overrides the date, for a named event`() {
        val data = Files.createTempDirectory("photobooth-data").toFile()
        val config = AppConfig.load(
            propsFile("data.dir=${data.path}", "data.subdir=tibo-uitzwaai"),
            LocalDate.of(2026, 8, 30),
        )

        assertEquals(File(data, "tibo-uitzwaai/merged"), config.mergedDir)
    }

    @Test
    fun `a blank data_subdir falls back to the date`() {
        val data = Files.createTempDirectory("photobooth-data").toFile()
        val config = AppConfig.load(
            propsFile("data.dir=${data.path}", "data.subdir=  "),
            LocalDate.of(2026, 1, 2),
        )

        assertEquals(File(data, "2026-01-02"), config.sessionDir)
    }
}
