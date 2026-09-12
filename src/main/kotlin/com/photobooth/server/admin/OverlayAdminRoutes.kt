package com.photobooth.server.admin

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The Overlays page: a gallery of this event's overlay PNGs with **upload**
 * (multipart), **delete**, and **set as default** (`overlay.file`). Uploads
 * and deletes hit `<event>/overlays/`; "set as default" writes
 * `overlay.file=overlays/<name>` and, since that key is live, reloads the
 * kiosk so the self-view preview updates at once.
 */
internal fun Route.overlayAdminRoutes(ctx: AdminContext) {

    get("/overlays") {
        call.respondText(renderOverlays(ctx), ContentType.Text.Html)
    }

    get("/overlays/thumb/{name}") {
        val file = call.parameters["name"]?.let(ctx::overlayFile)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(file.readBytes(), ContentType.Image.PNG)
    }

    post("/overlays/upload") {
        val results = mutableListOf<AdminContext.ImageSave>()
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem) {
                val bytes = part.streamProvider().use { it.readBytes() }
                results += ctx.saveOverlay(part.originalFileName ?: "overlay.png", bytes)
            }
            part.dispose()
        }

        val saved = results.filterIsInstance<AdminContext.ImageSave.Saved>()
        val rejected = results.filterIsInstance<AdminContext.ImageSave.Rejected>()
        val banner = when {
            results.isEmpty() -> Banner("info", "No file was selected.")
            rejected.isEmpty() -> Banner(
                "success",
                "Uploaded " + saved.joinToString(", ") { it.name + if (it.replaced) " (replaced)" else "" } + ".",
            )
            saved.isEmpty() -> Banner("error", rejected.joinToString("; ") { it.reason } + ".")
            else -> Banner(
                "info",
                "Uploaded " + saved.joinToString(", ") { it.name } +
                    "; skipped " + rejected.joinToString("; ") { it.reason } + ".",
            )
        }
        call.respondText(renderOverlays(ctx, banner), ContentType.Text.Html)
    }

    post("/overlays/delete") {
        val name = call.receiveParameters()["name"].orEmpty()
        val banner = if (ctx.deleteOverlay(name)) Banner("success", "Deleted $name.")
        else Banner("info", "Couldn't delete \"$name\".")
        call.respondText(renderOverlays(ctx, banner), ContentType.Text.Html)
    }

    post("/overlays/default") {
        val name = call.receiveParameters()["name"].orEmpty()
        val banner = if (ctx.setDefaultOverlay(name)) {
            ctx.reloadHook?.invoke()
            Banner("success", "$name is the default overlay now — applied to the kiosk.")
        } else {
            Banner("info", "Couldn't set \"$name\" as the default.")
        }
        call.respondText(renderOverlays(ctx, banner), ContentType.Text.Html)
    }
}

private fun renderOverlays(ctx: AdminContext, banner: Banner? = null): String {
    val e = AdminPages::esc
    val names = ctx.overlayNames()
    val current = ctx.defaultOverlayName()

    val body = buildString {
        append("<h1>Overlays</h1>")
        append("<p class=\"sub\"><code>events/").append(e(ctx.activeBundle.id))
        append("/overlays/</code> — transparent PNGs, one per choice a guest can make. ")
        append("The one marked <span class=\"pill\">default</span> is used when nobody picks.</p>")

        banner?.let { append("<p class=\"banner ").append(it.kind).append("\">").append(e(it.text)).append("</p>") }

        append("<form class=\"upload\" method=\"post\" action=\"/overlays/upload\" enctype=\"multipart/form-data\">")
        append("<input type=\"file\" name=\"files\" accept=\"image/png\" multiple required>")
        append("<button type=\"submit\">Upload PNG</button>")
        append("</form>")

        if (names.isEmpty()) {
            append("<p class=\"note\">No overlays yet — upload one above.</p>")
            return@buildString
        }

        append("<div class=\"grid-overlays\">")
        for (n in names) {
            val isDefault = n == current
            append("<figure>")
            append("<img loading=\"lazy\" src=\"/overlays/thumb/").append(e(n)).append("\" alt=\"\">")
            append("<figcaption>").append(e(n))
            if (isDefault) append(" <span class=\"pill\">default</span>")
            append("</figcaption>")
            append("<div class=\"tile-actions\">")
            if (!isDefault) {
                append("<form method=\"post\" action=\"/overlays/default\">")
                append("<input type=\"hidden\" name=\"name\" value=\"").append(e(n)).append("\">")
                append("<button type=\"submit\">Set default</button></form>")
            }
            append("<form method=\"post\" action=\"/overlays/delete\" onsubmit=\"return confirm('Delete ")
            append(e(n)).append("?')\">")
            append("<input type=\"hidden\" name=\"name\" value=\"").append(e(n)).append("\">")
            append("<button type=\"submit\" class=\"danger\">Delete</button></form>")
            append("</div>")
            append("</figure>")
        }
        append("</div>")
    }
    return AdminPages.page("Overlays", "/overlays", body)
}
