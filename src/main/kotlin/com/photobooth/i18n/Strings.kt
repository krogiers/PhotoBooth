package com.photobooth.i18n

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Every piece of guest-facing text — the JavaFX kiosk screens
 * ([com.photobooth.ui.BoothView]) and the small web pages a guest's phone
 * opens ([com.photobooth.server.DownloadServer]) — for one language.
 *
 * Text lives in `.properties` files, not hardcoded in Kotlin, so wording can
 * be tweaked or a whole new language added without a rebuild — the same
 * externalization pattern as `config/photobooth.properties` and
 * `config/overlay.png`:
 *  - `src/main/resources/i18n/<code>.properties` ships the built-in
 *    English/Dutch text (bundled into the jar).
 *  - `config/i18n/<code>.properties`, if present, is layered on top: add
 *    just the keys you want to change to override a few words, or every key
 *    (see the bundled `en.properties` for the full list) to add a language
 *    that isn't built in at all.
 *
 * Missing keys fall back to English rather than crashing — a partial
 * translation is better than a broken kiosk.
 */
class Strings private constructor(private val translations: Map<String, String>) {

    fun t(key: String, vararg args: Any): String {
        val template = translations[key] ?: error(
            "Missing i18n key '$key' — not in the active language, its config/i18n override, or the English fallback."
        )
        return if (args.isEmpty()) template else String.format(template, *args)
    }

    /**
     * A value read as a small list of options, split on [delimiter], trimmed,
     * blanks dropped. For keys that hold a set to choose from — e.g.
     * `smile_words`, the shout shown when the shutter fires; an operator
     * manages the set by editing that one line in `config/i18n/<lang>.properties`.
     *
     * Unlike [t], a missing key just yields an empty list rather than
     * throwing — a missing *list* means "no options", not a bug. Callers
     * decide the fallback (e.g. `list("smile_words").ifEmpty { … }`).
     */
    fun list(key: String, delimiter: Char = ','): List<String> =
        (translations[key] ?: "").split(delimiter).map(String::trim).filter(String::isNotEmpty)

    /** The full merged key→text map (bundled ← language ← event override), for the admin panel's read-only text view. */
    fun asMap(): Map<String, String> = translations.toSortedMap()

    companion object {
        private const val FALLBACK_LANGUAGE = "en"

        /** Language codes with text bundled in the jar. Others work too via an `i18n/<code>.properties` override. */
        val BUILT_IN: List<String> = listOf("en", "nl")

        /**
         * The bundled text for [code] — the English base, then that language's
         * bundled file if it's built in — with **no** event override applied.
         * The admin text editor's "default" column and the base it diffs an
         * override against.
         */
        fun bundledDefaults(code: String): Map<String, String> {
            val merged = LinkedHashMap(loadBundled(FALLBACK_LANGUAGE).orEmpty())
            if (code != FALLBACK_LANGUAGE) loadBundled(code)?.let(merged::putAll)
            return merged
        }

        fun load(languageCode: String, overridesDir: File = File("config/i18n")): Strings {
            val fallback = loadBundled(FALLBACK_LANGUAGE)
                ?: error("Missing bundled i18n resource for '$FALLBACK_LANGUAGE' — this is a packaging bug.")
            val bundled = loadBundled(languageCode)
            val override = loadFile(File(overridesDir, "$languageCode.properties"))

            if (languageCode != FALLBACK_LANGUAGE && bundled == null && override == null) {
                error(
                    "Unknown ui.language '$languageCode' — no bundled translation for it, and no " +
                        "${File(overridesDir, "$languageCode.properties")} either. Built-in languages: en, nl."
                )
            }

            // Layered so a config/i18n override can tweak just one or two words without having
            // to restate every other key, and an unfinished translation still falls back to English.
            val merged = LinkedHashMap<String, String>()
            merged.putAll(fallback)
            bundled?.let(merged::putAll)
            override?.let(merged::putAll)
            return Strings(merged)
        }

        private fun loadBundled(code: String): Map<String, String>? =
            Strings::class.java.classLoader.getResourceAsStream("i18n/$code.properties")?.use { stream ->
                readProperties(InputStreamReader(stream, StandardCharsets.UTF_8))
            }

        private fun loadFile(file: File): Map<String, String>? {
            if (!file.isFile) return null
            return file.reader(StandardCharsets.UTF_8).use { readProperties(it) }
        }

        private fun readProperties(reader: java.io.Reader): Map<String, String> {
            val props = Properties()
            props.load(reader)
            return props.stringPropertyNames().associateWith { props.getProperty(it) }
        }
    }
}
