package com.photobooth.server

import com.github.mustachejava.DefaultMustacheFactory
import com.github.mustachejava.MustacheNotFoundException
import com.github.mustachejava.MustacheResolver
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * Renders the small pages a guest's phone opens (`/p`, `/choose`, `/shoot/wait`,
 * `/last`). Markup lives in editable Mustache templates, not Kotlin strings,
 * with the same two-layer override model as [com.photobooth.i18n.Strings]:
 *
 *  - `src/main/resources/web/<name>` — the built-in defaults, bundled in the jar.
 *  - `config/web/<name>` — a per-event override (git-ignored). Drop a file
 *    here to replace the bundled one; the quickest rebrand is just
 *    `config/web/style.css`. Add `config/web/logo.png` (or `.svg`) and it
 *    shows on every page. No rebuild.
 *
 * Templates and assets are re-read from disk on every request — these pages
 * are scanned once every ~30s at a real booth, so there's no cache to
 * invalidate and edits show on the next refresh.
 *
 * `brandName` (from `web.brand.name`), `hasLogo`/`logoUrl` and `lang` are
 * merged into every template's model, so a template can show a wordmark and
 * logo without each call site passing them.
 */
class PhonePages(
    private val webDir: File = File("config/web"),
    private val brandName: String? = null,
    private val lang: String = "en",
) {
    private val resolver = LayeredResolver(webDir)

    fun render(template: String, model: Map<String, Any?> = emptyMap()): String {
        // A fresh factory per call so an edited config/web/ template is picked up
        // without a restart; compiling a page this small is a sub-millisecond cost.
        val mustache = DefaultMustacheFactory(resolver).compile(template)
        val logo = logoAssetName()
        val full = buildMap {
            put("lang", lang)
            put("brandName", brandName?.ifBlank { null })
            put("hasLogo", logo != null)
            put("logoUrl", logo?.let { "/web/$it" })
            put("hasBrand", brandName?.isNotBlank() == true || logo != null)
            putAll(model)
        }
        return StringWriter().also { mustache.execute(it, full).flush() }.toString()
    }

    /** Bytes + MIME type for `/web/{file}` — a stylesheet, logo or font, [webDir] winning over the bundled copy. */
    fun asset(name: String): Asset? {
        val type = CONTENT_TYPES[name.substringAfterLast('.', "").lowercase()] ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null

        val override = File(webDir, name)
        val bytes = when {
            override.isFile -> override.readBytes()
            else -> javaClass.classLoader.getResourceAsStream("web/$name")?.use { it.readBytes() } ?: return null
        }
        return Asset(bytes, type)
    }

    private fun logoAssetName(): String? =
        LOGO_NAMES.firstOrNull { File(webDir, it).isFile || javaClass.classLoader.getResource("web/$it") != null }

    data class Asset(val bytes: ByteArray, val contentType: String)

    private class LayeredResolver(private val overrideDir: File) : MustacheResolver {
        override fun getReader(resourceName: String): Reader {
            val override = File(overrideDir, resourceName)
            if (override.isFile) return override.reader(StandardCharsets.UTF_8)
            val bundled = javaClass.classLoader.getResourceAsStream("web/$resourceName")
                ?: throw MustacheNotFoundException(resourceName)
            return InputStreamReader(bundled, StandardCharsets.UTF_8)
        }
    }

    private companion object {
        val LOGO_NAMES = listOf("logo.svg", "logo.png", "logo.jpg", "logo.jpeg", "logo.webp")
        val CONTENT_TYPES = mapOf(
            "css" to "text/css; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "woff2" to "font/woff2",
        )
    }
}
