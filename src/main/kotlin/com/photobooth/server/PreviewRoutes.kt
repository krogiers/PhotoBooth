package com.photobooth.server

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * `/preview` — every phone page rendered with sample data, so the styling can
 * be tweaked on a phone over the tunnel without taking a real photo. Harmless
 * to leave mounted: it only ever shows fake data. The offline equivalent is
 * `./gradlew renderPhonePages` (see `PhonePagesPreview.kt`).
 */
internal fun Routing.previewRoutes(ctx: PhoneRoutingContext) {
    get("/preview") {
        respondPage(
            """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex"><title>Phone page preview</title>
            <link rel="stylesheet" href="/web/style.css"></head>
            <body><main><h1>Phone page preview</h1>
            <div class="grid" style="grid-template-columns:1fr">
              <a class="tile" href="/preview/photo"><span class="label">Download page (/p)</span></a>
              <a class="tile" href="/preview/choose"><span class="label">Layout picker (/choose)</span></a>
              <a class="tile" href="/preview/wait"><span class="label">Photo-incoming page (/shoot/wait)</span></a>
              <a class="tile" href="/preview/message"><span class="label">Message page (/last, errors)</span></a>
            </div></main></body></html>
            """.trimIndent(),
        )
    }
    get("/preview/photo") {
        respondPage(
            ctx.pages.render(
                "photo.mustache",
                mapOf(
                    "title" to ctx.strings.t("photo_page_title"),
                    "photoUrl" to SAMPLE_IMAGE,
                    "downloadUrl" to "#",
                    "downloadLabel" to ctx.strings.t("download_button"),
                    "againUrl" to "#",
                    "againLabel" to ctx.strings.t("another_photo"),
                ),
            ),
        )
    }
    get("/preview/choose") {
        val sample = listOf("Classic", "Party", "Flags & Bunting", "Sweden").mapIndexed { i, label ->
            mapOf("label" to label, "thumbUrl" to SAMPLE_IMAGE, "chooseUrl" to "#", "selected" to (i == 1))
        }
        respondPage(ctx.chooserPagePreview(sample))
    }
    get("/preview/wait") {
        respondPage(
            ctx.pages.render(
                "message.mustache",
                mapOf(
                    "heading" to ctx.strings.t("photo_incoming"),
                    "subtext" to ctx.strings.t("photo_incoming_hint"),
                    "emoji" to "⏳",
                ),
            ),
        )
    }
    get("/preview/message") {
        respondPage(
            ctx.pages.render("message.mustache", mapOf("heading" to ctx.strings.t("no_photo_yet"), "emoji" to "📷")),
        )
    }
}

/** Neutral placeholder image for the preview renders (and `PhonePagesPreview`), so they need no real photo or overlay files. */
internal const val SAMPLE_IMAGE =
    "data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='600'%20height='400'%3E" +
        "%3Crect%20width='600'%20height='400'%20fill='%232e7dff'/%3E%3Ctext%20x='50%25'%20y='50%25'%20" +
        "fill='white'%20font-family='sans-serif'%20font-size='36'%20text-anchor='middle'%20" +
        "dominant-baseline='middle'%3Esample%3C/text%3E%3C/svg%3E"
