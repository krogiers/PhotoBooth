package com.photobooth.server

import com.photobooth.session.OverlaySelection

/*
 * The `/choose` page model, shared by [overlayRoutes] (live) and
 * [previewRoutes] (sample data). Rendered from `choose.mustache`.
 */

/** The live layout picker: current pick outlined, "Take the photo" button on when a shoot can be triggered. */
internal fun PhoneRoutingContext.chooserPage(selection: OverlaySelection): String {
    val current = selection.current().name
    val overlays = selection.available().map {
        mapOf(
            "label" to it.nameWithoutExtension,
            "thumbUrl" to "/overlays/${it.name}",
            "chooseUrl" to "/choose/${it.name}",
            "selected" to (it.name == current),
        )
    }
    return renderChooser(overlays, canShoot = onStartRequested != null, shootUrl = "/shoot")
}

/** [chooserPage] with the button forced on and inert links — for the `/preview` render. */
internal fun PhoneRoutingContext.chooserPagePreview(overlays: List<Map<String, Any?>>): String =
    renderChooser(overlays, canShoot = true, shootUrl = "#")

private fun PhoneRoutingContext.renderChooser(
    overlays: List<Map<String, Any?>>,
    canShoot: Boolean,
    shootUrl: String,
): String = pages.render(
    "choose.mustache",
    mapOf(
        "title" to strings.t("choose_overlay_page_title"),
        "heading" to strings.t("choose_overlay_heading"),
        "overlays" to overlays,
        "canShoot" to canShoot,
        "shootUrl" to shootUrl,
        "shootLabel" to strings.t("take_photo_button"),
    ),
)
