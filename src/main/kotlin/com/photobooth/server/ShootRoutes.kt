package com.photobooth.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Phone-triggered shoot — the "Take the photo" button on `/choose`:
 *
 *  - `/shoot` calls the same start hook the arcade button uses
 *    ([PhoneRoutingContext.onStartRequested]). If it started, it records what
 *    the latest photo was and redirects to `/shoot/wait`; if the booth was
 *    busy (usually the previous photo's result screen is still up) it says so
 *    and links back, rather than making the phone wait for a photo that isn't
 *    coming.
 *  - `/shoot/wait` meta-refreshes every 2s until a newer photo than the one
 *    from `/shoot` exists, then redirects the phone straight to it. No
 *    JavaScript — a server-driven poll. Gives up after [SHOOT_WAIT_TIMEOUT_MS].
 *
 * A shoot started this way is exactly as real as one from the arcade button;
 * there's no check that whoever tapped it is in front of the camera.
 */
internal fun Routing.shootRoutes(ctx: PhoneRoutingContext) {
    get("/shoot") {
        val handler = ctx.onStartRequested ?: return@get call.respond(HttpStatusCode.NotFound)

        val before = ctx.lastPhotoFileName.get()
        if (!handler()) {
            return@get respondPage(ctx.messagePage("shoot_busy", emoji = "⏳"))
        }
        ctx.phoneShoot.set(PhoneRoutingContext.PhoneShoot(before, System.currentTimeMillis()))
        call.respondRedirect("/shoot/wait")
    }
    get("/shoot/wait") {
        val shoot = ctx.phoneShoot.get()
        val current = ctx.lastPhotoFileName.get()
        when {
            shoot == null ->
                call.respondRedirect(if (ctx.overlaySelection != null) "/choose" else "/last")

            current != null && current != shoot.before -> {
                ctx.phoneShoot.set(null)
                call.respondRedirect("/p/$current")
            }

            System.currentTimeMillis() - shoot.startedAtMs > SHOOT_WAIT_TIMEOUT_MS -> {
                ctx.phoneShoot.set(null)
                respondPage(ctx.messagePage("photo_incoming_failed", emoji = "😕"))
            }

            else -> respondPage(
                ctx.pages.render(
                    "message.mustache",
                    mapOf(
                        "heading" to ctx.strings.t("photo_incoming"),
                        "subtext" to ctx.strings.t("photo_incoming_hint"),
                        "emoji" to "⏳",
                        "metaRefresh" to "2; url=/shoot/wait",
                    ),
                ),
            )
        }
    }
}

/** A `message.mustache` render with a link back to `/choose` (or `/last` when there's no picker). */
private fun PhoneRoutingContext.messagePage(headingKey: String, emoji: String): String {
    val toPicker = overlaySelection != null
    return pages.render(
        "message.mustache",
        mapOf(
            "heading" to strings.t(headingKey),
            "emoji" to emoji,
            "linkUrl" to if (toPicker) "/choose" else "/last",
            "linkLabel" to strings.t(if (toPicker) "choose_overlay_page_title" else "scan_last_photo"),
        ),
    )
}

/** How long `/shoot/wait` keeps polling before it gives up on the photo (a normal shoot is well under 10s). */
private const val SHOOT_WAIT_TIMEOUT_MS = 25_000L
