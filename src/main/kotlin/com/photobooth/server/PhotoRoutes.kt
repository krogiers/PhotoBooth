package com.photobooth.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

/**
 * Photo delivery for a guest's phone:
 *
 *  - `/photos/{name}` — the raw merged image.
 *  - `/download/{name}` — the same file as an attachment, so a tap saves it.
 *  - `/p/{name}` — the download page (preview + button), what a QR points at.
 *  - `/last` — recovery for a guest who didn't scan their QR in time: it
 *    redirects to whichever photo was taken most recently. A single
 *    ever-changing slot, not a gallery.
 */
internal fun Routing.photoRoutes(ctx: PhoneRoutingContext) {
    get("/photos/{name}") {
        serveFileOrNotFound(ctx.photosDir, call.parameters["name"])
    }
    get("/download/{name}") {
        serveFileOrNotFound(ctx.photosDir, call.parameters["name"], forceDownload = true)
    }
    get("/p/{name}") {
        val name = call.parameters["name"]
        if (name == null || !File(ctx.photosDir, name).normalize().let { it.startsWith(ctx.photosDir) && it.isFile }) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        respondPage(
            ctx.pages.render(
                "photo.mustache",
                buildMap {
                    put("title", ctx.strings.t("photo_page_title"))
                    put("photoUrl", "/photos/$name")
                    put("downloadUrl", "/download/$name")
                    put("downloadLabel", ctx.strings.t("download_button"))
                    // An explicit way back to the layout picker, so a guest taking a second
                    // photo doesn't rely on the browser Back button (which lands on a stale
                    // "photo incoming" page).
                    if (ctx.overlaySelection != null) {
                        put("againUrl", "/choose")
                        put("againLabel", ctx.strings.t("another_photo"))
                    }
                },
            ),
        )
    }
    get("/last") {
        val name = ctx.lastPhotoFileName.get()
        if (name == null) {
            respondPage(
                ctx.pages.render("message.mustache", mapOf("heading" to ctx.strings.t("no_photo_yet"), "emoji" to "📷")),
                HttpStatusCode.NotFound,
            )
        } else {
            call.respondRedirect("/p/$name")
        }
    }
}
