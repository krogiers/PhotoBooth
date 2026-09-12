package com.photobooth.server.admin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The admin panel's pages. Read-only in this phase: it shows the active
 * event, all events, the raw `photobooth.properties`, the merged guest text
 * and the overlays. Later phases add POST endpoints that write back into the
 * active event bundle.
 *
 * Mounted by [AdminServer] inside an `authenticate("admin")` block.
 */
internal fun Route.adminRoutes(ctx: AdminContext) {

    get("/") {
        val meta = ctx.activeBundle.meta()
        val body = buildString {
            append("<h1>Dashboard</h1>")
            append("<p class=\"sub\">Read-only for now — editing lands in a later phase.</p>")
            append("<div class=\"cards\">")
            card("Active event", "${AdminPages.esc(meta.name)}<br><span class=\"pill\">${AdminPages.esc(ctx.activeBundle.id)}</span>")
            card("Event date", AdminPages.esc(meta.date ?: "—"))
            card("Guest server port", ctx.config.serverPort.toString())
            card("Admin port", "${ctx.config.adminPort} <span class=\"pill\">LAN only</span>")
            card("Language", AdminPages.esc(ctx.config.language))
            card("Overlay picker", if (ctx.config.overlaySelectionEnabled) "on" else "off")
            card("Second screen", if (ctx.config.secondScreenEnabled) "on" else "off")
            card("Overlays in bundle", ctx.overlayNames().size.toString())
            append("</div>")
            if (!meta.notes.isNullOrBlank()) {
                append("<p class=\"note\">").append(AdminPages.esc(meta.notes)).append("</p>")
            }
        }
        call.respondHtml("Dashboard", "/", body)
    }

    get("/events") {
        val activeId = ctx.activeBundle.id
        val rows = ctx.store.list().map { b ->
            val m = b.meta()
            listOf(
                b.id + (if (b.id == activeId) "  ← active" else ""),
                m.name,
                m.date ?: "",
                m.notes ?: "",
            )
        }
        val body = buildString {
            append("<h1>Events</h1>")
            append("<p class=\"sub\">Bundles under <code>events/</code>. <code>events/_active</code> selects one; switching from here is a later phase.</p>")
            append(
                if (rows.isEmpty()) "<p class=\"note\">No event bundles found.</p>"
                else AdminPages.table(listOf("Id", "Name", "Date", "Notes"), rows, rawCols = emptySet()),
            )
        }
        call.respondHtml("Events", "/events", body)
    }

    // /settings   → SettingsRoutes.kt
    // /text       → TextAdminRoutes.kt
    // /overlays   → OverlayAdminRoutes.kt
}

private suspend fun ApplicationCall.respondHtml(title: String, activePath: String, body: String) =
    respondText(AdminPages.page(title, activePath, body), ContentType.Text.Html)

private fun StringBuilder.card(title: String, valueHtml: String) {
    append("<div class=\"card\"><h2>").append(AdminPages.esc(title))
    append("</h2><div class=\"big\">").append(valueHtml).append("</div></div>")
}
