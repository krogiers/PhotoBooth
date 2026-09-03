package com.photobooth.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.PipelineContext
import java.io.File

/*
 * Cross-cutting plumbing shared by the phone-facing route groups in this
 * package. Route-group-specific logic stays in its own `*Routes.kt` file.
 */

/** Respond with an HTML page, `no-store` so Cloudflare's edge doesn't cache what is otherwise private event content. */
internal suspend fun PipelineContext<Unit, ApplicationCall>.respondPage(
    html: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(html, ContentType.Text.Html, status)
}

/**
 * Serve [requestedName] from [dir], or `404`. [dir] is flat, but this is
 * public (Cloudflare tunnel) so a `../` escape is rejected regardless. With
 * [forceDownload] the response is `Content-Disposition: attachment` so an
 * `<a href>` tap saves the file instead of opening it full-screen.
 *
 * [cacheControl] defaults to `no-store` — right for private event photos.
 * Static assets that repeat on every page load (overlay thumbnails) pass a
 * cacheable value so the phone doesn't re-fetch them on every layout pick.
 */
internal suspend fun PipelineContext<Unit, ApplicationCall>.serveFileOrNotFound(
    dir: File,
    requestedName: String?,
    forceDownload: Boolean = false,
    cacheControl: String = "no-store",
) {
    val requested = requestedName ?: return call.respond(HttpStatusCode.NotFound)
    val file = File(dir, requested).normalize()
    if (!file.startsWith(dir) || !file.isFile) {
        return call.respond(HttpStatusCode.NotFound)
    }
    call.response.header(HttpHeaders.CacheControl, cacheControl)
    if (forceDownload) {
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString(),
        )
    }
    call.respondFile(file)
}
