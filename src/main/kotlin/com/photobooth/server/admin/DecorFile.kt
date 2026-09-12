package com.photobooth.server.admin

import java.io.File

/**
 * Reads / writes the numbered `decor.<n>.*` image-layer block in an event's
 * `photobooth.properties` as one managed sub-document. Everything else in the
 * file — comments, key order, `decor.title.*` (which is `title`, not a
 * number, so it's left alone) — is preserved; the layer block is stripped and
 * re-emitted, renumbered `1..M` with no gaps.
 */
internal object DecorFile {

    data class Layer(
        val file: String,
        val position: String = "center",
        val width: String = "",
        val height: String = "",
        val rotate: String = "",
    )

    private val LAYER_LINE = Regex("""^\s*decor\.(\d+)\.(file|position|width|height|rotate)\s*=(.*)$""")

    /** Existing image layers, in numeric order, gaps removed, blank-`file` entries dropped. */
    fun read(propsFile: File): List<Layer> {
        if (!propsFile.isFile) return emptyList()
        val byN = sortedMapOf<Int, MutableMap<String, String>>()
        propsFile.readLines().forEach { line ->
            val m = LAYER_LINE.find(line) ?: return@forEach
            byN.getOrPut(m.groupValues[1].toInt()) { mutableMapOf() }[m.groupValues[2]] = m.groupValues[3].trim()
        }
        return byN.values
            .map {
                Layer(
                    file = it["file"].orEmpty(),
                    position = it["position"]?.ifBlank { null } ?: "center",
                    width = it["width"].orEmpty(),
                    height = it["height"].orEmpty(),
                    rotate = it["rotate"].orEmpty(),
                )
            }
            .filter { it.file.isNotBlank() }
    }

    /** Replace the layer block with [layers], renumbered `1..M`; keep every other line. */
    fun write(propsFile: File, layers: List<Layer>) {
        val kept = if (propsFile.isFile) {
            propsFile.readLines().filterNot { LAYER_LINE.matches(it) }
        } else {
            listOf("# PhotoBooth event settings — written by the admin panel.")
        }

        val out = StringBuilder(kept.joinToString("\n").trimEnd())
        if (layers.isNotEmpty()) {
            out.append("\n\n# --- decor image layers (managed by the admin panel) ---\n")
            layers.forEachIndexed { i, l ->
                val n = i + 1
                out.append("decor.$n.file=").append(l.file).append('\n')
                out.append("decor.$n.position=").append(l.position.ifBlank { "center" }).append('\n')
                if (l.width.isNotBlank()) out.append("decor.$n.width=").append(l.width).append('\n')
                if (l.height.isNotBlank()) out.append("decor.$n.height=").append(l.height).append('\n')
                if (l.rotate.isNotBlank() && l.rotate.toDoubleOrNull() != 0.0) {
                    out.append("decor.$n.rotate=").append(l.rotate).append('\n')
                }
            }
        }
        propsFile.parentFile?.mkdirs()
        propsFile.writeText(out.toString().trimEnd() + "\n")
    }
}
