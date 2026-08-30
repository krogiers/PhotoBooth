package com.photobooth.ui

import com.photobooth.camera.PreviewSource
import com.photobooth.config.DecorConfig
import com.photobooth.config.DecorPosition
import com.photobooth.config.KioskLayout
import com.photobooth.i18n.Strings
import com.photobooth.session.PhotoSession
import com.photobooth.util.daemonExecutor
import com.photobooth.util.daemonScheduler
import javafx.animation.ScaleTransition
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.PixelBuffer
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.util.Duration
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * Full-screen kiosk UI. [previewView] is a permanent bottom layer showing the
 * live self-view (see [PreviewSource]) whenever [previewSource] is running;
 * [idleOverlay], [countdownOverlay] and [resultOverlay] are shown/hidden on
 * top of it depending on state, rather than being swapped in and out of the
 * scene graph — simpler lifecycle, and the self-view never has to be torn
 * down and rebuilt between states.
 *
 * @param previewSource null disables the self-view entirely (e.g. no camera at all).
 * @param resultDisplaySeconds how long the merged photo + QR stay up before returning to idle.
 * @param strings guest-facing text, already resolved to one language — see [Strings].
 * @param chooseOverlayQrCode PNG bytes for the "scan to choose your layout" QR, shown on the left of the idle screen; null hides it.
 * @param lastPhotoQrCode PNG bytes for the "missed your QR?" recovery QR (`DownloadServer`'s `/last`), shown on the right of the idle screen; null hides it.
 * @param decor per-event dressing for the idle screen (bunting, a graphic, a titled font) — see [DecorConfig]. [DecorConfig.NONE] draws nothing.
 */
class BoothView(
    private val stage: Stage,
    private val session: PhotoSession,
    private val countdownSeconds: Int,
    private val previewSource: PreviewSource? = null,
    private val resultDisplaySeconds: Int = 15,
    private val strings: Strings,
    private val chooseOverlayQrCode: ByteArray? = null,
    private val lastPhotoQrCode: ByteArray? = null,
    private val decor: DecorConfig = DecorConfig.NONE,
    /** Where the two QRs and "tap to start" sit on the idle screen — see [KioskLayout]. */
    private val kioskLayout: KioskLayout = KioskLayout.DEFAULT,
) {
    // fitWidth/fitHeight are bound to the scene-graph root in show(), so the self-view fills
    // whatever display the kiosk actually runs on rather than assuming one resolution.
    private val previewView = ImageView().apply { isPreserveRatio = false }
    // One reusable ARGB surface for the self-view, so a ~15 fps feed doesn't allocate a fresh
    // Image (and re-upload a texture) every frame. Recreated only when the frame size changes.
    private var previewSurface: PreviewSurface? = null

    // The currently-active overlay, composited live on top of the self-view so guests see
    // going in exactly what their photo will look like — updated via [updateOverlayPreview],
    // wired in Main.kt to fire whenever the active overlay changes (a phone pick, or a shoot
    // finishing and falling back to the default). isMouseTransparent so it never blocks clicks.
    private val overlayPreviewView = ImageView().apply {
        isPreserveRatio = false
        isMouseTransparent = true
    }

    // A solid badge behind the countdown number/"Smile!" so it stays legible over any camera
    // background, rather than relying on a drop shadow alone against a live, unpredictable feed.
    // Styled by `.countdown-badge` / `.countdown-number` in kiosk.css.
    private val countdownBadge = Region().apply { styleClass += "countdown-badge" }
    private val countdownLabel = Label().apply { styleClass += "countdown-number" }
    private val log = LoggerFactory.getLogger(BoothView::class.java)
    private val idleOverlay = buildIdleOverlay()
    private val countdownOverlay = StackPane(countdownBadge, countdownLabel).apply {
        alignment = Pos.CENTER
        isVisible = false
        isMouseTransparent = true
    }
    private val resultOverlay = StackPane().apply { isVisible = false }
    private val root = StackPane(previewView, overlayPreviewView, idleOverlay, countdownOverlay, resultOverlay).apply { alignment = Pos.CENTER }
    private val resultGeneration = AtomicLong(0)
    // Guards against two shoots ever running at once — a fast double-tap, the arcade button
    // firing while a phone-triggered shoot (see requestStart) is already underway, or vice versa.
    private val sessionInProgress = AtomicBoolean(false)

    // One shoot at a time runs on this executor; the result screen's auto-dismiss is a single
    // scheduled task we keep a handle on so an early tap can cancel it. Both are shut down in close().
    private val sessionExecutor = daemonExecutor("photo-session")
    private val resultTimer = daemonScheduler("result-timer")
    private var pendingDismiss: ScheduledFuture<*>? = null

    // The shout shown when the shutter fires — one picked at random per shoot from the
    // `smile_words` list (managed per event in config/i18n/<lang>.properties).
    private val smileWords: List<String> = strings.list("smile_words").ifEmpty { listOf("Smile!") }

    fun show() {
        root.setOnMouseClicked { startSession() }

        // Fill the actual surface, whatever its resolution — no assumed 1024×768.
        for (view in listOf(previewView, overlayPreviewView)) {
            view.fitWidthProperty().bind(root.widthProperty())
            view.fitHeightProperty().bind(root.heightProperty())
        }

        // PhotoBoothApp already created the Scene (the loading screen) and put the stage on
        // screen / full-screen. Swap in our root rather than replacing the whole Scene, which
        // would trigger a second full-screen transition and re-show the "press ESC" hint.
        stage.scene.root = root

        previewSource?.start()

        session.onCountdownTick = { secondsLeft -> Platform.runLater { showCountdown(secondsLeft) } }
        // Runs on PhotoSession's background thread, and blocks until the camera is free — see PreviewSource.stop's contract.
        // A failure here must not sink the shoot silently: let it surface as the session's capture error.
        session.onBeforeCapture = { previewSource?.stop() }
        session.onPhotoReady = { merged, qrPng, _ -> Platform.runLater { showResult(merged, qrPng) } }
    }

    /** Called by a touchscreen tap and by [com.photobooth.gpio.Gpio]'s button listener; ignored if a shoot is already running. */
    fun startSession() {
        requestStart()
    }

    /**
     * Same as [startSession], but reports whether it actually started —
     * `false` when a shoot was already in progress. Safe to call from any
     * thread (the atomic check happens here; touching the UI is marshalled
     * onto the JavaFX thread). Used by `DownloadServer`'s `/shoot` route
     * (the phone layout page's "Take the photo" button) so a guest
     * triggering it remotely gets an honest answer back.
     */
    fun requestStart(): Boolean {
        if (!sessionInProgress.compareAndSet(false, true)) return false
        Platform.runLater {
            showCountdown(countdownSeconds)
            sessionExecutor.execute {
                try {
                    session.run()
                } catch (e: Throwable) {
                    // Without this a capture failure would leave the UI stuck on "Smile!" with
                    // sessionInProgress never cleared — the booth bricked until a restart.
                    log.error("Photo session failed — returning to the idle screen", e)
                    Platform.runLater { abortToIdle() }
                }
            }
        }
        return true
    }

    /**
     * Feeds a freshly grabbed self-view frame into [previewView]. Wired as
     * [previewSource]'s `onFrame` by `PhotoBoothApp` right after constructing
     * this view. Runs on the preview source's reader thread: the JPEG is
     * **decoded here, off the FX thread**, and only the cheap buffer copy is
     * marshalled onto it.
     */
    fun updatePreviewFrame(jpegBytes: ByteArray) {
        val frame = runCatching { ImageIO.read(ByteArrayInputStream(jpegBytes)) }.getOrNull() ?: return
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return
        val argb = IntArray(w * h)
        frame.getRGB(0, 0, w, h, argb, 0, w) // opaque 0xFFRRGGBB — an MJPEG frame has no alpha

        Platform.runLater {
            if (resultOverlay.isVisible) return@runLater
            runCatching {
                var surface = previewSurface
                if (surface == null || surface.width != w || surface.height != h) {
                    surface = PreviewSurface(w, h)
                    previewSurface = surface
                    previewView.image = surface.image
                }
                surface.push(argb)
            }.onFailure { log.warn("Self-view frame render failed", it) }
        }
    }

    /**
     * Updates the overlay composited live on top of the self-view. `Main.kt`
     * wires this to [com.photobooth.session.OverlaySelection]'s `onChanged`,
     * and calls it once up front with the default overlay so the preview is
     * correct even before anyone picks one on their phone.
     */
    fun updateOverlayPreview(overlayFile: File) {
        Platform.runLater {
            runCatching { overlayPreviewView.image = Image(overlayFile.toURI().toString()) }
        }
    }

    /**
     * The idle screen: a per-event [decor] layer (bunting, graphic, title)
     * behind the functional layer. The two QRs and "Tap to start" are placed
     * by alignment from [kioskLayout] (`kiosk.*.position` config), each with a
     * small edge inset. Empty areas of both layers don't pick, so a tap
     * anywhere but on a QR falls through to start a shoot.
     */
    private fun buildIdleOverlay(): StackPane {
        val functional = StackPane()

        fun place(node: Node, at: DecorPosition) {
            StackPane.setAlignment(node, at.toPos())
            StackPane.setMargin(node, EDGE_INSET)
            functional.children.add(node)
        }

        chooseOverlayQrCode?.let { place(qrTile(it, strings.t("scan_choose_overlay")), kioskLayout.layoutQr) }
        lastPhotoQrCode?.let { place(qrTile(it, strings.t("scan_last_photo")), kioskLayout.recoveryQr) }
        place(Label(strings.t("tap_to_start")).apply { styleClass += "tap-hint" }, kioskLayout.tapHint)

        return StackPane(buildDecorLayer(), functional)
    }

    /**
     * Per-event idle-screen dressing from [decor] — a back-to-front list of
     * freely-placed, optionally-rotated image layers ([DecorConfig.items]),
     * plus an optional text title. A missing/broken file is logged and
     * skipped rather than crashing the kiosk. The whole layer is
     * mouse-transparent so it never eats a tap.
     */
    private fun buildDecorLayer(): StackPane {
        val layer = StackPane().apply { isMouseTransparent = true }
        if (decor.isEmpty) return layer

        for (item in decor.items) {
            val img = loadDecorImage(item.file) ?: continue
            val view = ImageView(img).apply {
                isPreserveRatio = !(item.width != null && item.height != null)
                rotate = item.rotate
                item.width?.let { w ->
                    if (w.percentOfScreen) fitWidthProperty().bind(layer.widthProperty().multiply(w.amount))
                    else fitWidth = w.amount
                }
                item.height?.let { h ->
                    if (h.percentOfScreen) fitHeightProperty().bind(layer.heightProperty().multiply(h.amount))
                    else fitHeight = h.amount
                }
            }
            StackPane.setAlignment(view, item.position.toPos())
            layer.children.add(view)
        }

        decor.titleText?.takeIf { it.isNotBlank() }?.let { text ->
            val label = Label(text).apply {
                styleClass += "decor-title" // shadow in kiosk.css; font / colour / position from decor.*
                isWrapText = true
                font = decorFont(decor.titleFontFile, decor.titleSize)
                textFill = runCatching { Color.web(decor.titleColor) }.getOrDefault(Color.WHITE)
            }
            StackPane.setAlignment(label, decor.titlePosition.toPos())
            StackPane.setMargin(label, Insets(28.0))
            layer.children.add(label)
        }

        return layer
    }

    private fun loadDecorImage(file: File): Image? {
        if (!file.isFile) {
            log.warn("decor: file not found: {}", file.absolutePath)
            return null
        }
        return runCatching { Image(file.toURI().toString()) }
            .onFailure { log.warn("decor: could not load image {} ({})", file.absolutePath, it.message) }
            .getOrNull()
    }

    private fun decorFont(file: File?, size: Double): Font {
        if (file == null) return Font.font(size)
        if (!file.isFile) {
            log.warn("decor: title font not found: {} — using the default", file.absolutePath)
            return Font.font(size)
        }
        return file.inputStream().use { Font.loadFont(it, size) }
            ?: run {
                log.warn("decor: could not load title font {} — using the default", file.absolutePath)
                Font.font(size)
            }
    }

    private fun DecorPosition.toPos(): Pos = when (this) {
        DecorPosition.TOP_LEFT -> Pos.TOP_LEFT
        DecorPosition.TOP_CENTER -> Pos.TOP_CENTER
        DecorPosition.TOP_RIGHT -> Pos.TOP_RIGHT
        DecorPosition.CENTER_LEFT -> Pos.CENTER_LEFT
        DecorPosition.CENTER -> Pos.CENTER
        DecorPosition.CENTER_RIGHT -> Pos.CENTER_RIGHT
        DecorPosition.BOTTOM_LEFT -> Pos.BOTTOM_LEFT
        DecorPosition.BOTTOM_CENTER -> Pos.BOTTOM_CENTER
        DecorPosition.BOTTOM_RIGHT -> Pos.BOTTOM_RIGHT
    }

    private fun qrTile(qrPng: ByteArray, caption: String): VBox {
        val image = ImageView(Image(ByteArrayInputStream(qrPng))).apply { fitWidth = QR_TILE_WIDTH; isPreserveRatio = true }
        val label = Label(caption).apply { styleClass += "qr-caption" }
        return VBox(image, label).apply {
            styleClass += "qr-tile"
            maxWidth = Region.USE_PREF_SIZE
        }
    }

    private fun showCountdown(secondsLeft: Int) {
        idleOverlay.isVisible = false
        resultOverlay.isVisible = false
        countdownOverlay.isVisible = true
        countdownLabel.text = if (secondsLeft > 0) secondsLeft.toString() else smileWords.random()

        ScaleTransition(Duration.millis(280.0), countdownLabel).apply {
            fromX = 1.5; fromY = 1.5; toX = 1.0; toY = 1.0
            play()
        }
    }

    private fun showResult(merged: File, qrPng: ByteArray) {
        countdownOverlay.isVisible = false
        idleOverlay.isVisible = false
        // previewSource is already stopped, via session.onBeforeCapture, before the shot was even taken.

        val generation = resultGeneration.incrementAndGet()

        val photoView = ImageView(Image(merged.toURI().toString())).apply { fitWidth = RESULT_PHOTO_WIDTH; isPreserveRatio = true }
        val qrView = ImageView(Image(ByteArrayInputStream(qrPng))).apply { fitWidth = RESULT_QR_WIDTH; isPreserveRatio = true }
        val caption = Label(strings.t("scan_download")).apply { styleClass += "result-caption" }
        resultOverlay.children.setAll(
            Region().apply { styleClass += "result-scrim" }
                .also { it.prefWidthProperty().bind(root.widthProperty()); it.prefHeightProperty().bind(root.heightProperty()) },
            VBox(photoView, qrView, caption).apply { styleClass += "result-content" },
        )
        // A tap during the result screen skips the rest of the wait and returns straight to
        // idle, ready for the next group — consumed so it doesn't also start a new session
        // via root's own click handler.
        resultOverlay.setOnMouseClicked { event ->
            event.consume()
            dismissResult(generation)
        }
        resultOverlay.isVisible = true

        pendingDismiss?.cancel(false)
        pendingDismiss = resultTimer.schedule(
            { Platform.runLater { dismissResult(generation) } },
            resultDisplaySeconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    /** No-ops if [generation] is stale — i.e. this result was already dismissed (by a tap) or superseded by a newer one. */
    private fun dismissResult(generation: Long) {
        if (resultGeneration.get() != generation) return
        pendingDismiss?.cancel(false)
        resultOverlay.isVisible = false
        idleOverlay.isVisible = true
        restartPreview()
        // Cleared last, and unconditionally, so a preview that fails to restart still leaves
        // the booth able to take the next photo rather than stuck "in progress" forever.
        sessionInProgress.set(false)
    }

    /** Bring the screen back to idle after a session that ended in an error rather than a photo. */
    private fun abortToIdle() {
        countdownOverlay.isVisible = false
        resultOverlay.isVisible = false
        idleOverlay.isVisible = true
        restartPreview()
        sessionInProgress.set(false)
    }

    private fun restartPreview() {
        runCatching { previewSource?.start() }
            .onFailure { log.warn("Self-view preview failed to restart", it) }
    }

    /** Stops the session executor and result timer. Call from the stage's close handler. */
    fun close() {
        pendingDismiss?.cancel(false)
        resultTimer.shutdownNow()
        sessionExecutor.shutdownNow()
    }

    /**
     * A fixed-size ARGB surface backing [previewView]. The direct NIO buffer
     * is shared with the JavaFX texture, so [push] copies one decoded frame in
     * and asks JavaFX to re-upload it in place — no per-frame `Image`.
     */
    private class PreviewSurface(val width: Int, val height: Int) {
        private val nio: IntBuffer = ByteBuffer
            .allocateDirect(width * height * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        private val pixelBuffer = PixelBuffer(width, height, nio, PixelFormat.getIntArgbPreInstance())
        val image = WritableImage(pixelBuffer)

        /** Copy one decoded frame into the shared buffer and push it to the texture. FX thread only. */
        fun push(argb: IntArray) {
            // Absolute bulk put: fill the buffer without moving its position, so `updateBuffer`
            // still sees a full buffer to upload (a relative put() would leave 0 remaining).
            nio.put(0, argb, 0, minOf(argb.size, nio.capacity()))
            pixelBuffer.updateBuffer { null }
        }
    }

    private companion object {
        // JavaFX can't size an ImageView from CSS, so these three widths (px) live here rather
        // than in kiosk.css. Everything else visual is a style class in that stylesheet.
        const val QR_TILE_WIDTH = 160.0
        const val RESULT_PHOTO_WIDTH = 500.0
        const val RESULT_QR_WIDTH = 200.0

        /** Gap between an idle-screen element and the screen edge it's anchored to. */
        val EDGE_INSET: Insets = Insets(40.0)
    }
}
