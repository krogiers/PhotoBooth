package com.photobooth.server

import com.photobooth.i18n.Strings
import com.photobooth.session.OverlaySelection
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The handful of things every phone-facing route group needs, bundled so each
 * `*Routes(ctx)` file in this package takes one parameter instead of five.
 * Owned by [DownloadServer]; the route files are otherwise pure functions of
 * this context.
 */
internal class PhoneRoutingContext(
    val photosDir: File,
    val pages: PhonePages,
    /** `@Volatile var` so the admin panel's text editor can swap in re-loaded strings without a restart. */
    @Volatile var strings: Strings,
    val lastPhotoFileName: AtomicReference<String?>,
    val overlaySelection: OverlaySelection?,
) {
    /** In-flight phone-triggered shoot, so `/shoot/wait` can tell when *this* shoot's photo has landed. */
    val phoneShoot = AtomicReference<PhoneShoot?>(null)

    /**
     * Set by `Main.kt` once `BoothView` exists — see
     * [com.photobooth.ui.BoothView.requestStart]. Null hides the "Take the
     * photo" button on `/choose` and 404s `/shoot`. `@Volatile` because it's
     * written on the app thread and read on Netty's.
     */
    @Volatile
    var onStartRequested: (() -> Boolean)? = null

    class PhoneShoot(val before: String?, val startedAtMs: Long)
}
