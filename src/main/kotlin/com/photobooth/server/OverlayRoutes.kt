package com.photobooth.server

import com.photobooth.session.OverlaySelection
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The phone-side layout picker, mounted by [DownloadServer] only when overlay
 * selection is enabled:
 *
 *  - `/choose` — the grid (current pick outlined) plus the "Take the photo" button.
 *  - `/choose/{name}` — records a pick and redirects back to `/choose`.
 *  - `/overlays/{name}` — the thumbnails.
 *
 * See [chooserPage] for the page model and [com.photobooth.session.OverlaySelection]
 * for how a pick is held for the next shoot then reset.
 */
internal fun Routing.overlayRoutes(ctx: PhoneRoutingContext, selection: OverlaySelection) {
    get("/overlays/{name}") {
        // Static per event and repeated on every /choose load — let the phone cache them
        // so a layout pick doesn't re-download the whole gallery.
        serveFileOrNotFound(selection.overlaysDir, call.parameters["name"], cacheControl = "public, max-age=86400")
    }
    get("/choose") {
        respondPage(ctx.chooserPage(selection))
    }
    get("/choose/{name}") {
        val name = call.parameters["name"]
        if (name != null && selection.choose(name)) {
            call.respondRedirect("/choose")
        } else {
            respondPage(
                ctx.pages.render(
                    "message.mustache",
                    mapOf(
                        "heading" to ctx.strings.t("overlay_not_found"),
                        "emoji" to "🤔",
                        "linkUrl" to "/choose",
                        "linkLabel" to ctx.strings.t("choose_overlay_page_title"),
                    ),
                ),
                HttpStatusCode.NotFound,
            )
        }
    }
}
