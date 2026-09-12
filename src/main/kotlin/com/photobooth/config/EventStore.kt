package com.photobooth.config

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Owns `events/` — the set of [EventBundle]s and the `events/_active` pointer
 * that says which one the booth loads. The admin panel switches events by
 * writing that pointer ([setActive]); the booth reads it once at startup
 * ([active]).
 *
 * On first run it migrates a legacy flat `config/` (the pre-events layout)
 * into `events/default/` so nothing is lost on upgrade.
 */
class EventStore(
    private val root: File = File("events"),
    /** The pre-events flat config dir, migrated into `events/default/` on first run. Injectable for tests. */
    private val legacyConfigDir: File = File("config"),
) {

    private val log = LoggerFactory.getLogger(EventStore::class.java)
    private val activePointer = File(root, "_active")

    /** Every event bundle, `_`-prefixed entries skipped, sorted by id. */
    fun list(): List<EventBundle> =
        (root.listFiles { f -> f.isDirectory && !f.name.startsWith("_") } ?: emptyArray())
            .sortedBy { it.name }
            .map(::EventBundle)

    /**
     * The bundle to load: the `PHOTOBOOTH_EVENT` env var (or the
     * `photobooth.event` system property) wins if it names a real bundle —
     * handy for `./gradlew run -Pdev` and for pinning an event on the Pi
     * without editing `_active`. Otherwise `events/_active`, otherwise
     * `events/default/` (created, migrating a legacy `config/` if present).
     */
    fun active(): EventBundle {
        migrateLegacyConfigIfNeeded()

        val forced = (System.getenv("PHOTOBOOTH_EVENT") ?: System.getProperty("photobooth.event"))
            ?.trim()?.ifBlank { null }
        if (forced != null) {
            val dir = File(root, forced)
            if (dir.isDirectory) {
                log.info("Loading event '{}' (from PHOTOBOOTH_EVENT / -Pevent)", forced)
                return EventBundle(dir)
            }
            log.warn("PHOTOBOOTH_EVENT='{}' but events/{}/ doesn't exist — falling back to _active", forced, forced)
        }

        val named = activePointer.takeIf { it.isFile }
            ?.readText()?.lineSequence()?.firstOrNull()?.trim()?.ifBlank { null }
            ?.let { File(root, it) }
            ?.takeIf { it.isDirectory }
        if (named != null) return EventBundle(named)

        val fallback = File(root, DEFAULT_ID)
        fallback.mkdirs()
        if (!activePointer.isFile) writeActive(DEFAULT_ID)
        return EventBundle(fallback)
    }

    /** Point `events/_active` at [id]. */
    fun setActive(id: String) {
        require(File(root, id).isDirectory) { "No event bundle 'events/$id'" }
        writeActive(id)
        log.info("Active event is now '{}'", id)
    }

    private fun writeActive(id: String) {
        root.mkdirs()
        activePointer.writeText("$id\n")
    }

    /**
     * One-time lift of the old flat `config/` (photobooth.properties + the
     * overlays/decor/i18n/web/kiosk folders) into `events/default/`. Copies
     * (doesn't move) so the originals stay as a safety net; rewrites the
     * `config/…` path prefixes in the copied properties so relative resolution
     * against the bundle works.
     */
    private fun migrateLegacyConfigIfNeeded() {
        val legacy = legacyConfigDir
        val default = File(root, DEFAULT_ID)
        // Keyed on the properties file, not the dir: `./gradlew seedConfig` may have
        // already created events/default/{i18n,web,kiosk}/ without migrating anything.
        if (File(default, "photobooth.properties").isFile || !File(legacy, "photobooth.properties").isFile) return

        val items = listOf("photobooth.properties", "overlay.png", "overlays", "decor", "i18n", "web", "kiosk")
        default.mkdirs()
        items.forEach { name ->
            val src = File(legacy, name)
            if (src.exists()) src.copyRecursively(File(default, name), overwrite = false)
        }

        File(default, "photobooth.properties").takeIf { it.isFile }?.let { p ->
            p.writeText(
                p.readText()
                    .replace("config/overlays", "overlays")
                    .replace("config/decor", "decor")
                    .replace("config/web", "web")
                    .replace("config/i18n", "i18n")
                    .replace("config/kiosk", "kiosk")
                    .replace("config/overlay.png", "overlay.png"),
            )
        }
        writeActive(DEFAULT_ID)
        log.info(
            "Migrated legacy config/ into events/{}/ — originals left in config/ (safe to delete once you've checked the booth).",
            DEFAULT_ID,
        )
    }

    companion object {
        const val DEFAULT_ID = "default"
    }
}
