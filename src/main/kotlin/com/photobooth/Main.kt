package com.photobooth

import com.photobooth.config.AppConfig
import com.photobooth.i18n.Strings
import com.photobooth.ui.BoothView
import com.photobooth.util.daemonExecutor
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
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

    override fun start(stage: Stage) {
        if (System.getenv("PHOTOBOOTH_WINDOWED") == null) {
            stage.isFullScreen = true
            stage.fullScreenExitHint = "" // no "press ESC" popup on a kiosk
        }

        val config = runCatching { AppConfig.load() }
            .getOrElse { return fail(stage, strings = null, cause = it) }
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

        stage.setOnCloseRequest {
            view.close()
            c.close()
        }
    }

    private fun fail(stage: Stage, strings: Strings?, cause: Throwable) {
        log.error("Startup failed", cause)
        val message = strings?.t("startup_failed")
            ?: "Couldn't start the booth. Check that cloudflared is installed and the machine is online."
        setContent(stage, messagePane(message), WIDTH, HEIGHT)
    }

    /** JavaFX calls this on exit — a safety net for the paths `setOnCloseRequest` doesn't cover. */
    override fun stop() {
        component?.close()
        bootstrap.shutdownNow()
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
