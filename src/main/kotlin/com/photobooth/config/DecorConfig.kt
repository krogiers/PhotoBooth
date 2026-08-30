package com.photobooth.config

import java.io.File
import java.util.Properties

/** Where a decoration is anchored on the idle screen. Parsed from `top-left`, `bottom-center`, … */
enum class DecorPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

    companion object {
        fun parse(value: String): DecorPosition {
            val norm = value.trim().replace('-', '_')
            return entries.firstOrNull { it.name.equals(norm, ignoreCase = true) }
                ?: error(
                    "Unknown decor position '$value' — expected one of " +
                        entries.joinToString { it.name.lowercase().replace('_', '-') }
                )
        }
    }
}

/**
 * Per-event dressing for the JavaFX idle screen — a bunting garland across the
 * top, one free-placed graphic (a landmark, mascot, …), and a title in a
 * custom font. Everything is optional and comes from `config/decor/` with no
 * rebuild, the same override idea as `config/web/` and `config/i18n/`. With
 * nothing configured the idle screen looks exactly as before.
 *
 * Kept free of JavaFX types on purpose (like the rest of `config/`): the
 * colour string and font file are resolved to `Color` / `Font` in
 * [com.photobooth.ui.BoothView].
 */
data class DecorConfig(
    /** Stretched across the top edge; null = no garland. */
    val buntingFile: File?,
    /** How tall the garland is drawn, in px. */
    val buntingHeight: Double,
    /** A single free-placed graphic; null = none. */
    val graphicFile: File?,
    val graphicPosition: DecorPosition,
    /** Drawn this tall in px, scaled proportionally. */
    val graphicHeight: Double,
    /** Shown as a heading somewhere on the screen; null/blank = no title. */
    val titleText: String?,
    /** `.ttf` / `.otf` for [titleText]; null = the default UI font. */
    val titleFontFile: File?,
    val titleSize: Double,
    /** Hex colour (`#rrggbb`), resolved with `Color.web` at render time. */
    val titleColor: String,
    val titlePosition: DecorPosition,
) {
    /** True when there is nothing at all to draw. */
    val isEmpty: Boolean
        get() = buntingFile == null && graphicFile == null && titleText.isNullOrBlank()

    companion object {
        val NONE = DecorConfig(
            buntingFile = null,
            buntingHeight = 140.0,
            graphicFile = null,
            graphicPosition = DecorPosition.BOTTOM_RIGHT,
            graphicHeight = 240.0,
            titleText = null,
            titleFontFile = null,
            titleSize = 64.0,
            titleColor = "#ffffff",
            titlePosition = DecorPosition.TOP_CENTER,
        )

        fun from(props: Properties): DecorConfig {
            fun str(key: String) = props.getProperty(key, "").trim().ifBlank { null }
            fun file(key: String) = str(key)?.let(::File)
            fun num(key: String, default: Double) = str(key)?.toDouble() ?: default
            fun pos(key: String, default: DecorPosition) = str(key)?.let(DecorPosition::parse) ?: default

            return DecorConfig(
                buntingFile = file("decor.bunting.file"),
                buntingHeight = num("decor.bunting.height", NONE.buntingHeight),
                graphicFile = file("decor.graphic.file"),
                graphicPosition = pos("decor.graphic.position", NONE.graphicPosition),
                graphicHeight = num("decor.graphic.height", NONE.graphicHeight),
                titleText = str("decor.title.text"),
                titleFontFile = file("decor.title.font"),
                titleSize = num("decor.title.size", NONE.titleSize),
                titleColor = str("decor.title.color") ?: NONE.titleColor,
                titlePosition = pos("decor.title.position", NONE.titlePosition),
            )
        }
    }
}
