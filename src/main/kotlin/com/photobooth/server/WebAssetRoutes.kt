package com.photobooth.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** `/web/{file}` — the phone pages' stylesheet, logo and fonts, `config/web/` winning over the bundled copy. See [PhonePages]. */
internal fun Routing.webAssetRoutes(ctx: PhoneRoutingContext) {
    get("/web/{file}") {
        val asset = call.parameters["file"]?.let(ctx.pages::asset)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytes(asset.bytes, ContentType.parse(asset.contentType))
    }
}
