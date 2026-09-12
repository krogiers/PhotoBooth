package com.photobooth.server.admin

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Reads / writes a **generated** `.properties` override file (the event's
 * `i18n/<lang>.properties`). These aren't hand-maintained reference files, so
 * there's nothing to preserve — the whole file is rewritten, sorted, one
 * `key=value` per line, UTF-8, with `\`, newlines, tabs and a leading space
 * escaped so `java.util.Properties.load` round-trips it exactly.
 */
internal object PropsIo {

    fun read(file: File): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!file.isFile) return out
        val p = Properties()
        file.reader(StandardCharsets.UTF_8).use(p::load)
        p.stringPropertyNames().sorted().forEach { out[it] = p.getProperty(it) }
        return out
    }

    fun write(file: File, entries: Map<String, String>) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder("# Managed by the PhotoBooth admin panel.\n")
        entries.toSortedMap().forEach { (k, v) ->
            sb.append(escapeKey(k)).append('=').append(escapeValue(v)).append('\n')
        }
        file.writeText(sb.toString(), StandardCharsets.UTF_8)
    }

    private fun escapeKey(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '\\', '=', ':', ' ', '#', '!' -> append('\\').append(c)
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun escapeValue(s: String): String = buildString {
        s.forEachIndexed { i, c ->
            when {
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == ' ' && i == 0 -> append("\\ ")
                else -> append(c)
            }
        }
    }
}
