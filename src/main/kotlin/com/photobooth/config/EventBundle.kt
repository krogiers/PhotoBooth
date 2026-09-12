package com.photobooth.config

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * One event's self-contained settings bundle, under `events/<id>/`:
 *
 * ```
 * events/<id>/
 *   event.properties     admin metadata (name, date, notes)
 *   photobooth.properties every AppConfig key, for this event
 *   overlay.png          the default overlay
 *   overlays/            selectable overlays
 *   decor/              idle-screen decor images + title font
 *   i18n/               guest-facing text (nl / en / …)
 *   web/                phone-page templates + css + logo
 *   kiosk/kiosk.css     kiosk styling
 *   data/               this event's photos (originals/ + merged/)
 * ```
 *
 * The bundled defaults in `src/main/resources/` stay the fallback layer under
 * everything here. [EventStore] decides which bundle is active
 * (`events/_active`). Kept free of JavaFX types like the rest of `config/`.
 */
class EventBundle(val dir: File) {
    val id: String get() = dir.name

    val propertiesFile: File get() = File(dir, "photobooth.properties")
    val i18nDir: File get() = File(dir, "i18n")
    val webDir: File get() = File(dir, "web")
    val kioskCss: File get() = File(dir, "kiosk/kiosk.css")

    /** Resolve a config path value against this bundle: an absolute path is left alone, a relative one is under [dir]. */
    fun resolve(pathValue: String): File =
        File(pathValue).let { if (it.isAbsolute) it else File(dir, pathValue) }

    /** `event.properties` metadata for the admin event list; every field optional. */
    fun meta(): EventMeta {
        val f = File(dir, "event.properties")
        if (!f.isFile) return EventMeta(name = id, date = null, notes = null)
        val p = Properties()
        f.reader(StandardCharsets.UTF_8).use(p::load)
        return EventMeta(
            name = p.getProperty("name")?.trim()?.ifBlank { null } ?: id,
            date = p.getProperty("date")?.trim()?.ifBlank { null },
            notes = p.getProperty("notes")?.trim()?.ifBlank { null },
        )
    }

    override fun equals(other: Any?): Boolean = other is EventBundle && other.dir == dir
    override fun hashCode(): Int = dir.hashCode()
    override fun toString(): String = "EventBundle($dir)"
}

/** The `event.properties` fields shown in the admin event list. */
data class EventMeta(val name: String, val date: String?, val notes: String?)
