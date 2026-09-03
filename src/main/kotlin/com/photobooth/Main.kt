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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val shuttingDown = AtomicBoolean(false)

    @Volatile
    private var component: PhotoBoothComponent? = null
    @Volatile
    private var boothView: BoothView? = null
    @Volatile
    private var boothStage: Stage? = null

    override fun start(stage: Stage) {
        // Ctrl-C on `./gradlew run` sends SIGINT; JavaFX's stop() may not run, so
        // make sure cloudflared / the server get killed anyway.
        Runtime.getRuntime().addShutdownHook(Thread({ teardown() }, "photobooth-shutdown"))

        boothStage = stage

        val config = runCatching { AppConfig.load() }
            .getOrElse {
                placeOnScreen(stage, Screen.getPrimary(), fullscreen = true, winW = null, winH = null) // no config yet — assume kiosk
                fail(stage, strings = null, cause = it) // shows the error scene (and shows the stage)
                goFullScreen(stage, on = System.getenv("PHOTOBOOTH_WINDOWED") == null)
                return
            }
        val boothFs = kioskFullscreen(config.boothFullscreen)
        placeOnScreen(
            stage, screenOrPrimary(config.boothDisplay, what = "booth"),
            fullscreen = boothFs, winW = config.windowWidth, winH = config.windowHeight,
        )

        val strings = runCatching { Strings.load(config.language) }
            .getOrElse { return fail(stage, strings = null, cause = it) }

        // The one and only Scene: later screens swap its root, never replace the Scene.
        setContent(stage, messagePane(strings.t("starting")))
        // Full-screen only *after* the stage is showing and positioned on the right
        // display, so it goes full-screen on that one and actually covers it edge to
        // edge (an undecorated window sized to the bounds left a strip uncovered).
        goFullScreen(stage, on = boothFs)

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
        boothView = view
        c.onStartRequested = { view.requestStart() }
        c.previewSource.onFrame = { bytes -> view.updatePreviewFrame(bytes) }
        // The live self-view always shows the overlay that will be used next — the default up
        // front, then whatever a guest picks on their phone, live as they pick it.
        view.updateOverlayPreview(c.overlaySelection?.current() ?: c.config.overlayFile)
        c.overlaySelection?.onChanged = { overlayFile -> view.updateOverlayPreview(overlayFile) }
        view.show()
        c.gpio.onButtonPressed { view.startSession() }

        if (c.config.secondScreenEnabled) installPicker(c, view)

        // Any window's close button tears down the *whole* app: Platform.exit()
        // closes every stage (booth + picker) and then fires stop() once, which
        // does the real teardown. Fixes "closing one screen leaves the other up".
        stage.setOnCloseRequest { Platform.exit() }
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

        val windowed = !c.config.pickerFullscreen || System.getenv("PHOTOBOOTH_WINDOWED") != null
        val target = Screen.getScreens().getOrNull(c.config.pickerDisplay)
        val screen: Screen
        val offset: Double
        when {
            target != null -> {
                screen = target; offset = 0.0
            }
            windowed -> {
                // Dev convenience: a second window on the primary display, offset so it isn't on
                // top of the booth window. Production (full-screen kiosk) never falls back.
                log.warn("screen.picker.display={} isn't present — opening the picker on the primary display (dev mode).", c.config.pickerDisplay)
                screen = Screen.getPrimary(); offset = 60.0
            }
            else -> {
                log.warn("secondscreen.enabled but display {} isn't present — no picker screen.", c.config.pickerDisplay)
                return
            }
        }

        val picker = PickerView(overlays, c.strings)
        picker.onShoot = { booth.requestStart() }
        booth.onSessionStateChanged = { inProgress -> picker.setBusy(inProgress) }
        // Fan the overlay-changed event out to the picker's highlight as well as the booth self-view.
        val boothOnChanged = overlays.onChanged
        overlays.onChanged = { f -> boothOnChanged(f); picker.refreshSelection() }

        val pickerFs = kioskFullscreen(c.config.pickerFullscreen) && offset == 0.0
        val stage = Stage()
        stage.title = "PhotoBooth — picker"
        stage.scene = Scene(picker.root, screen.bounds.width, screen.bounds.height)
            .apply { stylesheets.addAll(kioskStylesheets()) }
        if (offset > 0.0) {
            // Dev fallback only: a plain window on the primary, nudged clear of the booth window.
            stage.width = 960.0; stage.height = 720.0
            stage.x = screen.bounds.minX + offset
            stage.y = screen.bounds.minY + offset
        } else {
            placeOnScreen(
                stage, screen, fullscreen = pickerFs,
                winW = c.config.windowWidth, winH = c.config.windowHeight,
            )
        }
        stage.setOnCloseRequest { Platform.exit() }
        stage.show()
        goFullScreen(stage, on = pickerFs)
        // Making the picker full-screen can knock the booth out of it — put it back.
        if (kioskFullscreen(c.config.boothFullscreen)) {
            Platform.runLater { boothStage?.let { goFullScreen(it, on = true) } }
        }
        log.info("Picker screen up on display {} ({}).", c.config.pickerDisplay, if (offset > 0.0) "dev window" else "full-screen")
    }

    private fun fail(stage: Stage, strings: Strings?, cause: Throwable) {
        log.error("Startup failed", cause)
        val message = strings?.t("startup_failed")
            ?: "Couldn't start the booth. Check that cloudflared is installed and the machine is online."
        setContent(stage, messagePane(message))
    }

    /** JavaFX calls this once the toolkit exits (Platform.exit / last window closed / ⌘-Q). */
    override fun stop() = teardown()

    /**
     * Idempotent full shutdown. Reached from [stop] and from the JVM shutdown
     * hook (a `Ctrl-C` on `./gradlew run`, where `stop()` isn't guaranteed to
     * run) — the first caller wins, the rest are no-ops. Each step is guarded
     * so one slow/failing teardown can't strand the next: without this a
     * half-closed app keeps `cloudflared` and the Netty threads alive and the
     * process never exits.
     */
    private fun teardown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        log.info("Shutting down the booth...")
        runCatching { boothView?.close() }          // session executors + result timer
        runCatching { component?.close() }          // preview, download server, tunnel, GPIO — each bounded
        runCatching { bootstrap.shutdownNow() }
        runCatching { Platform.exit() }             // close any window still up (the Ctrl-C path)
    }

    /** The display at [index], or the primary one (with a warning) if that display isn't attached. */
    private fun screenOrPrimary(index: Int, what: String): Screen =
        Screen.getScreens().getOrNull(index) ?: Screen.getPrimary().also {
            log.warn("screen.{}.display={} isn't attached — the {} screen stays on the primary display.", what, index, what)
        }

    /** The configured `screen.*.fullscreen`, unless `PHOTOBOOTH_WINDOWED` forces a windowed dev run. */
    private fun kioskFullscreen(configured: Boolean): Boolean =
        configured && System.getenv("PHOTOBOOTH_WINDOWED") == null

    /**
     * Position [stage] on [screen] before it's shown. When [fullscreen] we only
     * set x/y (onto the target display) — the actual full-screen switch happens
     * in [goFullScreen] *after* `show()`, which is what makes it cover the
     * display edge to edge. When windowed we also size it: [winW]×[winH] if
     * given, else ~80% of the display's work area, centred — so nothing is
     * hard-coded to one resolution.
     */
    private fun placeOnScreen(stage: Stage, screen: Screen, fullscreen: Boolean, winW: Int?, winH: Int?) {
        if (fullscreen) {
            stage.x = screen.bounds.minX
            stage.y = screen.bounds.minY
        } else {
            val vb = screen.visualBounds
            stage.width = winW?.toDouble() ?: (vb.width * 0.8)
            stage.height = winH?.toDouble() ?: (vb.height * 0.8)
            stage.x = vb.minX + (vb.width - stage.width) / 2  // roughly centred on the display
            stage.y = vb.minY + (vb.height - stage.height) / 2
        }
    }

    /**
     * Real `Stage.isFullScreen` — it covers the display completely, which an
     * undecorated window sized to `Screen.bounds` didn't quite. JavaFX lets
     * only one Stage hold it at a time, so bringing up the picker can drop the
     * booth out of full-screen; [installPicker] re-asserts the booth's flag
     * right after. The exit hint is blanked so a kiosk shows no "press ESC".
     */
    private fun goFullScreen(stage: Stage, on: Boolean) {
        if (!on) return
        stage.isFullScreen = true
        stage.fullScreenExitHint = ""
    }

    /** Put [content] on screen: swap the root if the Scene already exists, else create it at the size already set on the stage. */
    private fun setContent(stage: Stage, content: Parent) {
        val scene = stage.scene
        if (scene == null) {
            val w = stage.width.takeIf { it.isFinite() && it > 0 } ?: 1024.0
            val h = stage.height.takeIf { it.isFinite() && it > 0 } ?: 768.0
            stage.scene = Scene(content, w, h).apply { stylesheets.addAll(kioskStylesheets()) }
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
}

fun main(args: Array<String>) {
    Application.launch(PhotoBoothApp::class.java, *args)
}
