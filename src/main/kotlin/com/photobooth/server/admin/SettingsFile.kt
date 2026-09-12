package com.photobooth.server.admin

import java.io.File

/**
 * Reads / merges the scalar keys in an event's `photobooth.properties`
 * **without disturbing anything else** — comments, blank lines, key order, the
 * numbered `decor.<n>.*` layer block and any key the admin schema doesn't
 * know are all preserved. A changed key is rewritten in place; a brand-new
 * key is appended under a marker comment.
 */
internal object SettingsFile {

    /** Current `key=value` pairs (last wins), comments/blanks ignored. */
    fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = LinkedHashMap<String, String>()
        file.readLines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!") || '=' !in t) return@forEach
            out[t.substringBefore('=').trim()] = t.substringAfter('=').trim()
        }
        return out
    }

    /**
     * Write [changes] (already filtered to real deltas by the caller) into
     * [file], preserving every other line. Returns the keys it wrote.
     */
    fun write(file: File, changes: Map<String, String>): Set<String> {
        if (changes.isEmpty()) return emptySet()
        val remaining = changes.keys.toMutableSet()
        val out = StringBuilder()

        if (file.isFile) {
            file.readLines().forEach { line ->
                val t = line.trimStart()
                val eq = t.indexOf('=')
                val key = if (eq > 0 && !t.startsWith("#") && !t.startsWith("!")) t.substring(0, eq).trim() else null
                if (key != null && key in changes) {
                    out.append(key).append('=').append(changes.getValue(key)).append('\n')
                    remaining.remove(key)
                } else {
                    out.append(line).append('\n')
                }
            }
        } else {
            out.append("# PhotoBooth event settings — written by the admin panel.\n")
        }

        if (remaining.isNotEmpty()) {
            if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
            out.append("# --- added via the admin panel ---\n")
            remaining.sorted().forEach { out.append(it).append('=').append(changes.getValue(it)).append('\n') }
        }

        file.parentFile?.mkdirs()
        file.writeText(out.toString())
        return changes.keys.toSet()
    }
}
