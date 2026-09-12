package com.photobooth.server.admin

import com.photobooth.config.AppConfig
import com.photobooth.config.EventBundle
import com.photobooth.config.EventStore
import com.photobooth.i18n.Strings
import java.io.File

/**
 * Everything the (read-only, for now) admin routes need. Owned by
 * [AdminServer]; the route file is a pure function of this.
 *
 * The admin panel runs on its own port and is never fronted by the Cloudflare
 * tunnel — only the guest [com.photobooth.server.DownloadServer] port is.
 */
internal class AdminContext(
    val store: EventStore,
    val activeBundle: EventBundle,
    config: AppConfig,
    strings: Strings,
) {
    /** Re-read after a settings edit so the read views show the new values — see [refresh]. */
    @Volatile var config: AppConfig = config
        private set

    @Volatile var strings: Strings = strings
        private set

    /**
     * Set by `PhotoBoothApp` once the kiosk UI exists: rebuilds `BoothView`
     * (and the picker) from freshly-loaded config so live-reloadable keys
     * take effect without a restart. Null when there's no UI yet / no panel.
     * `@Volatile` — written on the app thread, read on Netty's.
     */
    @Volatile var reloadHook: (() -> Unit)? = null

    /** Re-load [config] / [strings] from the active bundle after its `photobooth.properties` was written. */
    fun refresh() {
        config = AppConfig.load(activeBundle)
        strings = Strings.load(config.language, activeBundle.i18nDir)
    }
    /** The merged guest-facing text (bundled ← language ← event override), sorted by key. */
    fun textEntries(): Map<String, String> = strings.asMap()

    /** Language codes offered in the text editor: built-in ∪ the active language ∪ any `i18n/<code>.properties` in the bundle. */
    fun languageCodes(): List<String> =
        (Strings.BUILT_IN + config.language +
            (activeBundle.i18nDir.listFiles { f -> f.isFile && f.extension == "properties" }
                ?.map { it.nameWithoutExtension } ?: emptyList()))
            .distinct().sorted()

    /** The event's override file for [lang] (may not exist yet). */
    fun i18nOverrideFile(lang: String): File = File(activeBundle.i18nDir, "$lang.properties")

    /** File names of the selectable overlays in the active event bundle. */
    fun overlayNames(): List<String> =
        config.overlaysDir.listFiles { f -> f.isFile && f.extension.lowercase() == "png" }
            ?.map(File::getName)?.sorted()
            ?: emptyList()

    /** One overlay file by name, or null if it isn't in the bundle's overlays dir (also guards against path traversal). */
    fun overlayFile(name: String): File? {
        if (name.isBlank() || '/' in name || '\\' in name || ".." in name) return null
        return File(config.overlaysDir, name).takeIf { it.isFile }
    }

    /** Name of the current default overlay when it lives in the overlays dir (so a gallery tile can be marked), else null. */
    fun defaultOverlayName(): String? {
        val f = config.overlayFile
        return f.name.takeIf {
            runCatching { f.parentFile?.canonicalFile == config.overlaysDir.canonicalFile }.getOrDefault(false)
        }
    }

    sealed interface ImageSave {
        data class Saved(val name: String, val replaced: Boolean) : ImageSave
        data class Rejected(val reason: String) : ImageSave
    }

    /** Validate ([bytes] is really a PNG, under the size cap) and write an overlay into the bundle's overlays dir. */
    fun saveOverlay(rawName: String, bytes: ByteArray): ImageSave = saveImage(config.overlaysDir, rawName, bytes)

    /** Delete an overlay from the bundle. Refuses names outside the overlays dir. */
    fun deleteOverlay(name: String): Boolean = overlayFile(name)?.delete() == true

    /** Point `overlay.file` at `overlays/<name>` in this event's photobooth.properties. Returns false for an unknown overlay. */
    fun setDefaultOverlay(name: String): Boolean {
        if (overlayFile(name) == null) return false
        SettingsFile.write(activeBundle.propertiesFile, mapOf("overlay.file" to "overlays/$name"))
        refresh()
        return true
    }

    // --- decor images (events/<id>/decor/) --------------------------------

    fun decorDir(): File = File(activeBundle.dir, "decor")

    fun decorImageNames(): List<String> =
        decorDir().listFiles { f -> f.isFile && f.extension.lowercase() == "png" }
            ?.map(File::getName)?.sorted() ?: emptyList()

    fun decorImageFile(name: String): File? {
        if (name.isBlank() || '/' in name || '\\' in name || ".." in name) return null
        return File(decorDir(), name).takeIf { it.isFile }
    }

    fun saveDecorImage(rawName: String, bytes: ByteArray): ImageSave = saveImage(decorDir(), rawName, bytes)

    // --- shared image write ---------------------------------------------

    private fun saveImage(dir: File, rawName: String, bytes: ByteArray): ImageSave {
        val name = cleanImageName(rawName) ?: return ImageSave.Rejected("\"$rawName\": not a usable file name")
        if (bytes.size > MAX_IMAGE_BYTES) return ImageSave.Rejected("$name is over ${MAX_IMAGE_BYTES / 1_000_000} MB")
        if (!looksLikePng(bytes)) return ImageSave.Rejected("$name is not a PNG")
        val target = File(dir.apply { mkdirs() }, name)
        val replaced = target.isFile
        target.writeBytes(bytes)
        return ImageSave.Saved(name, replaced)
    }

    private fun cleanImageName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base == "." || base == "..") return null
        val safe = base.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val withExt = if (safe.lowercase().endsWith(".png")) safe.dropLast(4) + ".png" else "$safe.png"
        return withExt.takeIf { it != ".png" }
    }

    private fun looksLikePng(b: ByteArray): Boolean =
        b.size >= 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private companion object {
        const val MAX_IMAGE_BYTES = 20_000_000
    }
}
