package com.photobooth.config

import java.io.File
import java.util.Properties

/**
 * Where something is anchored on the idle screen — a decoration
 * ([DecorConfig]) or a fixed element like a QR code ([KioskLayout]). Parsed
 * from `top-left`, `center-right`, `bottom-center`, …
 */
enum class DecorPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

    companion object {
        fun parse(value: String): DecorPosition {
            val norm = value.trim().replace('-', '_')
            return entries.firstOrNull { it.name.equals(norm, ignoreCase = true) }
                ?: error(
                    "Unknown position '$value' — expected one of " +
                        entries.joinToString { it.name.lowercase().replace('_', '-') }
                )
        }
    }
}

/** A width or height: a plain number is px, a trailing `%` makes it a fraction of the screen. */
data class DecorSize(val amount: Double, val percentOfScreen: Boolean) {
    companion object {
        fun parse(raw: String?): DecorSize? = raw?.trim()?.ifBlank { null }?.let {
            if (it.endsWith("%")) DecorSize(it.dropLast(1).trim().toDouble() / 100.0, percentOfScreen = true)
            else DecorSize(it.toDouble(), percentOfScreen = false)
        }
    }
}

/**
 * One image layer on the idle screen. Give a [width] or a [height] (the other
 * scales proportionally); give both to stretch. [rotate] is degrees clockwise
 * about the image's centre — e.g. `-12` tilts bunting up towards a corner.
 */
data class DecorItem(
    val file: File,
    val position: DecorPosition,
    val width: DecorSize?,
    val height: DecorSize?,
    val rotate: Double,
)

/**
 * Per-event dressing for the JavaFX idle screen — a list of freely-placed
 * image layers (`decor.<n>.*`) plus an optional text title (`decor.title.*`).
 * Everything optional, all from `config/decor/` with no rebuild, the same
 * override idea as `config/web/` and `config/i18n/`. With nothing configured
 * the idle screen looks exactly as before.
 *
 * Kept free of JavaFX types on purpose (like the rest of `config/`): sizes,
 * colour and font file are resolved to bindings / `Color` / `Font` in
 * [com.photobooth.ui.BoothView].
 */
data class DecorConfig(
    /** Drawn back-to-front; a missing file is skipped (logged) at render time. */
    val items: List<DecorItem>,
    /** Shown as a heading; null/blank = no text title (an image layer can be the title instead). */
    val titleText: String?,
    /** `.ttf` / `.otf` for [titleText]; null = the default UI font. */
    val titleFontFile: File?,
    val titleSize: Double,
    /** Hex colour (`#rrggbb`), resolved with `Color.web` at render time. */
    val titleColor: String,
    val titlePosition: DecorPosition,
) {
    /** True when there is nothing at all to draw. */
    val isEmpty: Boolean get() = items.isEmpty() && titleText.isNullOrBlank()

    companion object {
        /** How many `decor.<n>.*` layers are scanned for. */
        private const val MAX_ITEMS = 20

        val NONE = DecorConfig(
            items = emptyList(),
            titleText = null,
            titleFontFile = null,
            titleSize = 64.0,
            titleColor = "#ffffff",
            titlePosition = DecorPosition.TOP_CENTER,
        )

        fun from(props: Properties): DecorConfig {
            fun str(key: String) = props.getProperty(key, "").trim().ifBlank { null }

            val items = (1..MAX_ITEMS).mapNotNull { n ->
                val file = str("decor.$n.file")?.let(::File) ?: return@mapNotNull null
                DecorItem(
                    file = file,
                    position = str("decor.$n.position")?.let(DecorPosition::parse) ?: DecorPosition.CENTER,
                    width = DecorSize.parse(str("decor.$n.width")),
                    height = DecorSize.parse(str("decor.$n.height")),
                    rotate = str("decor.$n.rotate")?.toDouble() ?: 0.0,
                )
            }

            return DecorConfig(
                items = items,
                titleText = str("decor.title.text"),
                titleFontFile = str("decor.title.font")?.let(::File),
                titleSize = str("decor.title.size")?.toDouble() ?: NONE.titleSize,
                titleColor = str("decor.title.color") ?: NONE.titleColor,
                titlePosition = str("decor.title.position")?.let(DecorPosition::parse) ?: NONE.titlePosition,
            )
        }
    }
}
