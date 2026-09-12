package com.photobooth.server

import com.photobooth.i18n.Strings
import com.photobooth.session.OverlaySelection
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded HTTP server for everything a guest's phone talks to. It owns the
 * Netty engine and a [PhoneRoutingContext] (the shared dependencies); the
 * routes themselves live one concern per file in this package and are mounted
 * in [start]:
 *
 *  - [webAssetRoutes] — `/web/{file}`, the phone pages' stylesheet / logo / fonts.
 *  - [photoRoutes] — `/photos`, `/download`, `/p/{name}`, `/last`.
 *  - [shootRoutes] — `/shoot`, `/shoot/wait`.
 *  - [overlayRoutes] — `/choose`, `/choose/{name}`, `/overlays/{name}` (only when an [OverlaySelection] is set).
 *  - [previewRoutes] — `/preview`.
 *
 * Reached by a guest's phone through a public tunnel ([CloudflareTunnel]);
 * responses are `no-store` so Cloudflare's edge doesn't cache event content.
 * Pages are Mustache templates rendered by [PhonePages].
 */
class DownloadServer(
    private val port: Int,
    photosDir: File,
    overlaySelection: OverlaySelection? = null,
    strings: Strings,
    pages: PhonePages,
    lastPhotoFileName: AtomicReference<String?> = AtomicReference(null),
) {
    private val ctx = PhoneRoutingContext(photosDir, pages, strings, lastPhotoFileName, overlaySelection)
    private var server: NettyApplicationEngine? = null

    /**
     * Set by `Main.kt` once `BoothView` exists — see
     * [com.photobooth.ui.BoothView.requestStart]. Null hides the "Take the
     * photo" button on `/choose` and 404s `/shoot`.
     */
    var onStartRequested: (() -> Boolean)?
        get() = ctx.onStartRequested
        set(value) {
            ctx.onStartRequested = value
        }

    /** Swapped in by `PhotoBoothApp` after an admin text edit so the phone pages pick up new wording without a restart. */
    var strings: Strings
        get() = ctx.strings
        set(value) { ctx.strings = value }

    fun start() {
        server = embeddedServer(Netty, port = port) {
            routing {
                get("/") { call.respondText(ctx.strings.t("server_running"), ContentType.Text.Plain) }
                webAssetRoutes(ctx)
                photoRoutes(ctx)
                shootRoutes(ctx)
                ctx.overlaySelection?.let { overlayRoutes(ctx, it) }
                previewRoutes(ctx)
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
    }
}
