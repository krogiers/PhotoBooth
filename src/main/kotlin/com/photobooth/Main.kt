package com.photobooth

import com.photobooth.config.AppConfig
import com.photobooth.i18n.Strings
import com.photobooth.ui.BoothView
import com.photobooth.ui.PickerView
import com.photobooth.util.daemonExecutor
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.stage.Screen
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The JavaFX shell. Deliberately thin: `start()` shows a loading screen
 * immediately, then builds the whole app graph
 * ([PhotoBoothComponent.build] — which blocks for a few seconds on the
 * Cloudflare tunnel) on a background executor so the FX Application Thread is
 * never blocked. Once it's ready the real [BoothView] replaces the loading
 * screen; if it fails, an error screen does.
 *
 * The message screens are styled by `kiosk/kiosk.css` (bundled) with a
 * `config/kiosk/kiosk.css` override layered on top — no rebuild.
 */
class PhotoBoothApp : Application() {

    private val log = LoggerFactory.getLogger(PhotoBoothApp::class.java)
    private val bootstrap = daemonExecutor("bootstrap")

    @Volatile
    private var component: PhotoBoothComponent? = null
    private var pickerStage: Stage? = null

    override fun start(stage: Stage) {
        val config = runCatching { AppConfig.load() }
            .onFailure { applyWindowMode(stage, fullscreen = true) } // no config yet — assume kiosk
            .getOrElse { return fail(stage, strings = null, cause = it) }
        placeOnDisplay(stage, config.boothDisplay, what = "booth")
        applyWindowMode(stage, fullscreen = config.windowFullscreen)

        val strings = runCatching { Strings.load(config.language) }
            .getOrElse { return fail(stage, strings = null, cause = it) }

        // The one and only Scene: later screens swap its root, never replace the Scene, so the
        // stage doesn't re-enter full-screen (and re-show its hint) each time.
        setContent(stage, messagePane(strings.t("starting")), config.windowWidth, config.windowHeight)

        bootstrap.execute {
            val built = runCatching { PhotoBoothComponent.build(config, strings) }
            Platform.runLater {
                built.onSuccess { installBooth(stage, it) }
                    .onFailure { fail(stage, strings, it) }
                bootstrap.shutdown()
            }
        }
    }

    private fun installBooth(stage: Stage, c: PhotoBoothComponent) {
        component = c
        val view = BoothView(
            stage,
            c.session,
            countdownSeconds = c.config.countdownSeconds,
            previewSource = c.previewSource,
            resultDisplaySeconds = c.config.resultDisplaySeconds,
            strings = c.strings,
            chooseOverlayQrCode = c.chooseOverlayQr,
            lastPhotoQrCode = c.lastPhotoQr,
            decor = c.config.decor,
            kioskLayout = c.config.kioskLayout,
        )
        c.onStartRequested = { view.requestStart() }
        c.previewSource.onFrame = { bytes -> view.updatePreviewFrame(bytes) }
        // The live self-view always shows the overlay that will be used next — the default up
        // front, then whatever a guest picks on their phone, live as they pick it.
        view.updateOverlayPreview(c.overlaySelection?.current() ?: c.config.overlayFile)
        c.overlaySelection?.onChanged = { overlayFile -> view.updateOverlayPreview(overlayFile) }
        view.show()
        c.gpio.onButtonPressed { view.startSession() }

        if (c.config.secondScreenEnabled) installPicker(c, view)

        stage.setOnCloseRequest {
            pickerStage?.close()
            view.close()
            c.close()
        }
    }

    /**
     * The optional second display: the layout gallery + a red "take the
     * photo" button. It appears **only** on `screen.picker.display` — if that
     * display isn't present, or overlay selection is off, there's simply no
     * picker screen.
     */
    private fun installPicker(c: PhotoBoothComponent, booth: BoothView) {
        val overlays = c.overlaySelection ?: run {
            log.warn("secondscreen.enabled but overlay.selection.enabled=false — no picker screen (it has no gallery).")
            return
        }
        val screen = Screen.getScreens().getOrNull(c.config.pickerDisplay) ?: run {
            log.warn("secondscreen.enabled but display {} isn't present — no picker screen.", c.config.pickerDisplay)
            return
        }

        val picker = PickerView(overlays, c.strings)
        picker.onShoot = { booth.requestStart() }
        booth.onSessionStateChanged = { inProgress -> picker.setBusy(inProgress) }
        // Fan the overlay-changed event out to the picker's highlight as well as the booth self-view.
        val boothOnChanged = overlays.onChanged
        overlays.onChanged = { f -> boothOnChanged(f); picker.refreshSelection() }

        val stage = Stage()
        stage.x = screen.bounds.minX
        stage.y = screen.bounds.minY
        stage.scene = Scene(picker.root, screen.bounds.width, screen.bounds.height)
            .apply { stylesheets.addAll(kioskStylesheets()) }
        applyWindowMode(stage, fullscreen = c.config.windowFullscreen)
        stage.setOnCloseRequest { pickerStage = null }
        stage.show()
        pickerStage = stage
    }

    private fun fail(stage: Stage, strings: Strings?, cause: Throwable) {
        log.error("Startup failed", cause)
        val message = strings?.t("startup_failed")
            ?: "Couldn't start the booth. Check that cloudflared is installed and the machine is online."
        setContent(stage, messagePane(message), WIDTH, HEIGHT)
    }

    /** JavaFX calls this on exit — a safety net for the paths `setOnCloseRequest` doesn't cover. */
    override fun stop() {
        pickerStage?.close()
        component?.close()
        bootstrap.shutdownNow()
    }

    /**
     * Move [stage] onto the given display index before it's shown. A missing
     * index is only a warning — the primary display is the fallback (the
     * booth must run somewhere; the picker screen, by contrast, is skipped).
     */
    private fun placeOnDisplay(stage: Stage, index: Int, what: String) {
        val screen = Screen.getScreens().getOrNull(index) ?: run {
            log.warn("screen.{}.display={} isn't present — {} screen stays on the primary display.", what, index, what)
            return
        }
        stage.x = screen.bounds.minX
        stage.y = screen.bounds.minY
    }

    /** Full-screen unless `window.fullscreen=false`, or the `PHOTOBOOTH_WINDOWED` env var forces windowed for a quick dev run. */
    private fun applyWindowMode(stage: Stage, fullscreen: Boolean) {
        val kiosk = fullscreen && System.getenv("PHOTOBOOTH_WINDOWED") == null
        stage.isFullScreen = kiosk
        if (kiosk) stage.fullScreenExitHint = "" // no "press ESC" popup on a kiosk
    }

    /** Put [content] on screen: swap the root if the Scene already exists (keeps full-screen), else create it. */
    private fun setContent(stage: Stage, content: Parent, width: Int, height: Int) {
        val scene = stage.scene
        if (scene == null) {
            stage.scene = Scene(content, width.toDouble(), height.toDouble())
                .apply { stylesheets.addAll(kioskStylesheets()) }
        } else {
            scene.root = content
        }
        if (!stage.isShowing) stage.show()
    }

    private fun messagePane(text: String): Parent {
        val label = Label(text).apply { styleClass += "kiosk-message" }
        return StackPane(label).apply {
            styleClass += "kiosk-root"
            label.maxWidthProperty().bind(widthProperty().multiply(0.8))
        }
    }

    /** Bundled `kiosk/kiosk.css`, then the optional `config/kiosk/kiosk.css` override (later = wins). */
    private fun kioskStylesheets(): List<String> = buildList {
        javaClass.getResource("/kiosk/kiosk.css")?.toExternalForm()?.let(::add)
        File("config/kiosk/kiosk.css").takeIf { it.isFile }?.toURI()?.toString()?.let(::add)
    }

    private companion object {
        const val WIDTH = 1024
        const val HEIGHT = 768
    }
}

fun main(args: Array<String>) {
    Application.launch(PhotoBoothApp::class.java, *args)
}
