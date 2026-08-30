package com.photobooth.camera

/**
 * Something that can feed the idle/countdown self-view a steady stream of
 * JPEG frames — either a real continuous stream
 * ([com.photobooth.camera.impl.FfmpegPreviewStream],
 * [com.photobooth.camera.impl.RpicamPreviewStream]) or, as a fallback,
 * repeated single-shot captures ([com.photobooth.camera.impl.PreviewLoop]).
 * [com.photobooth.ui.BoothView] only knows this interface, not which kind is
 * behind it.
 *
 * A source that touches real camera hardware must be [stop]ped — and have
 * fully released the device — before the real shutter capture runs; see how
 * [com.photobooth.session.PhotoSession.onBeforeCapture] is wired to this in
 * `Main.kt`.
 */
interface PreviewSource {
    /** Settable before [start] — called with each new frame's raw JPEG bytes, from a background thread. */
    var onFrame: (ByteArray) -> Unit

    fun start()

    /** Must not return until any camera device this source holds has been released. */
    fun stop()
}
