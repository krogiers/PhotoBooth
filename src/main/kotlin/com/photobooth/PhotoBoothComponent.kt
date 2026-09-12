package com.photobooth

import com.photobooth.camera.Camera
import com.photobooth.camera.PreviewSource
import com.photobooth.camera.impl.FfmpegPreviewStream
import com.photobooth.camera.impl.ImagesnapCamera
import com.photobooth.camera.impl.LibcameraCamera
import com.photobooth.camera.impl.PlaceholderCamera
import com.photobooth.camera.impl.PreviewLoop
import com.photobooth.camera.impl.RpicamPreviewStream
import com.photobooth.camera.impl.SynchronizedCamera
import com.photobooth.config.AppConfig
import com.photobooth.config.CameraType
import com.photobooth.config.EventBundle
import com.photobooth.config.EventStore
import com.photobooth.gpio.Gpio
import com.photobooth.gpio.impl.Pi4jGpioController
import com.photobooth.gpio.impl.SimulatedGpioController
import com.photobooth.i18n.Strings
import com.photobooth.imaging.OverlayCompositor
import com.photobooth.qrcode.QrCodeGenerator
import com.photobooth.server.CloudflareTunnel
import com.photobooth.server.DownloadServer
import com.photobooth.server.PhonePages
import com.photobooth.server.admin.AdminContext
import com.photobooth.server.admin.AdminServer
import com.photobooth.session.OverlaySelection
import com.photobooth.session.PhotoSession
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger(PhotoBoothComponent::class.java)
private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

/**
 * The composition root: everything the app is made of *except* the JavaFX UI.
 * [build] assembles the whole graph — config, camera, GPIO, download server,
 * and the Cloudflare tunnel (whose startup blocks for a few seconds) — so it
 * must be called **off the JavaFX Application Thread** (see `PhotoBoothApp`,
 * which runs it on a background executor behind a loading screen).
 *
 * `PhotoBoothApp` then reads the fields it needs to construct `BoothView` and
 * wires the few callbacks that need the view. [close] tears the graph down.
 */
class PhotoBoothComponent internal constructor(
    val config: AppConfig,
    /** The active event bundle this graph was built from — see [EventBundle]. */
    val bundle: EventBundle,
    val strings: Strings,
    val gpio: Gpio,
    val session: PhotoSession,
    val previewSource: PreviewSource,
    val overlaySelection: OverlaySelection?,
    /** "Scan to choose your layout" QR as PNG bytes; null when overlay selection is off. Encodes this run's tunnel host. */
    val chooseOverlayQr: ByteArray?,
    /** "Missed your QR?" recovery QR (`/last`) as PNG bytes. Encodes this run's tunnel host. */
    val lastPhotoQr: ByteArray,
    private val downloadServer: DownloadServer,
    private val tunnel: CloudflareTunnel,
    /** The LAN admin panel, or null when `admin.enabled=false` / no password — see [AdminServer]. */
    private val adminServer: AdminServer?,
    /** The admin panel's shared context (only when [adminServer] is up) — carries the [onReloadRequested] hook. */
    private val adminContext: AdminContext?,
) : AutoCloseable {

    /**
     * The trigger the phone `/shoot` button calls (wired by `PhotoBoothApp`
     * to `BoothView.requestStart`). Ignored unless `remote_start.enabled`.
     */
    var onStartRequested: (() -> Boolean)?
        get() = downloadServer.onStartRequested
        set(value) {
            downloadServer.onStartRequested = value.takeIf { config.remoteStartEnabled }
        }

    /**
     * Set by `PhotoBoothApp`: called (on a Netty thread) after the admin panel
     * writes a *live* settings change, to rebuild the kiosk UI from freshly
     * loaded config. No-op when there's no admin panel.
     */
    var onReloadRequested: (() -> Unit)?
        get() = adminContext?.reloadHook
        set(value) { adminContext?.reloadHook = value }

    /** Point the phone pages at re-loaded [Strings] after an admin text edit (kiosk text is refreshed by rebuilding `BoothView`). */
    fun refreshStrings(strings: Strings) {
        downloadServer.strings = strings
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Idempotent — may be hit from both `PhotoBoothApp.stop()` and its JVM shutdown hook. Each step is guarded so one failure can't skip the rest. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { previewSource.stop() }
        runCatching { adminServer?.stop() }
        runCatching { downloadServer.stop() }
        runCatching { tunnel.stop() }
        runCatching { gpio.close() }
    }

    companion object {
        /**
         * Builds the full non-UI graph. Blocks while the Cloudflare tunnel
         * comes up (up to ~20s) — call this off the JavaFX thread. Throws if
         * `cloudflared` never reports a public URL.
         */
        fun build(
            store: EventStore = EventStore(),
            bundle: EventBundle = store.active(),
            config: AppConfig = AppConfig.load(bundle),
            strings: Strings = Strings.load(config.language, bundle.i18nDir),
        ): PhotoBoothComponent {
            log.info("Loading event '{}' from {}", bundle.id, bundle.dir)
            val camera = SynchronizedCamera(selectCamera(config))
            val gpio = selectGpio(config)
            val compositor = OverlayCompositor()
            val qr = QrCodeGenerator()
            val overlaySelection = if (config.overlaySelectionEnabled) {
                OverlaySelection(config.overlaysDir, config.overlayFile)
            } else null

            val lastPhotoFileName = AtomicReference<String?>(null)
            val phonePages = PhonePages(webDir = bundle.webDir, brandName = config.webBrandName, lang = config.language)
            val downloadServer = DownloadServer(
                config.serverPort,
                config.mergedDir,
                overlaySelection,
                strings,
                phonePages,
                lastPhotoFileName,
            ).apply { start() }

            val tunnel = CloudflareTunnel(config.serverPort)

            // From here on a failure (the tunnel's ~20s timeout is the likely one) must not
            // leak the already-bound server port, the tunnel subprocess or the GPIO claim —
            // no PhotoBoothComponent gets constructed to close them, so do it here.
            try {
                log.info("Starting a Cloudflare quick tunnel, this takes a few seconds...")
                val baseUrl = tunnel.start()
                log.info("Tunnel ready — photos will be served publicly at {}", baseUrl)

                // /p/{name} is a small page with a preview + Download button, not the raw image —
                // a tap on a bare image link just opens it full-screen on most phones.
                val downloadUrlProvider: (String) -> String = { fileName ->
                    lastPhotoFileName.set(fileName) // so /last — the idle screen's "missed your QR?" fallback — can find it
                    "$baseUrl/p/$fileName"
                }

                val session = PhotoSession(config, camera, gpio, compositor, qr, downloadUrlProvider, overlaySelection)

                val chooseOverlayQr = if (overlaySelection != null) qr.pngBytes("$baseUrl/choose") else null
                val lastPhotoQr = qr.pngBytes("$baseUrl/last")

                val adminContext = AdminContext(store, bundle, config, strings)
                val adminServer = startAdminServer(config, adminContext)

                return PhotoBoothComponent(
                    config = config,
                    bundle = bundle,
                    strings = strings,
                    gpio = gpio,
                    session = session,
                    previewSource = selectPreviewSource(config, camera),
                    overlaySelection = overlaySelection,
                    chooseOverlayQr = chooseOverlayQr,
                    lastPhotoQr = lastPhotoQr,
                    downloadServer = downloadServer,
                    tunnel = tunnel,
                    adminServer = adminServer,
                    adminContext = adminContext.takeIf { adminServer != null },
                )
            } catch (e: Throwable) {
                runCatching { tunnel.stop() }
                runCatching { downloadServer.stop() }
                runCatching { gpio.close() }
                throw e
            }
        }
    }
}

/**
 * Starts the LAN admin panel on its own port, or returns null when it's off
 * (`admin.enabled=false`) or misconfigured (blank `admin.password`). A failure
 * to bind the port is logged, not fatal — the booth runs without the panel.
 */
private fun startAdminServer(config: AppConfig, ctx: AdminContext): AdminServer? {
    if (!config.adminEnabled) return null
    val password = config.adminPassword
    if (password.isNullOrBlank()) {
        log.warn("admin.enabled=true but admin.password is blank — the admin panel stays off.")
        return null
    }
    return runCatching {
        AdminServer(config.adminPort, password, ctx).apply { start() }
    }.getOrElse {
        log.warn("Admin panel failed to start on port {} ({}) — continuing without it.", config.adminPort, it.message)
        null
    }
}

/**
 * Picks the [Camera] per `camera.type`. `auto` (default) detects: the real Pi
 * camera on Linux, otherwise a Mac webcam via `imagesnap` if installed, else
 * a synthetic placeholder.
 */
private fun selectCamera(config: AppConfig): Camera {
    // rpicam wants a numeric --camera index; imagesnap wants a -d device name.
    val webcamDevice = webcamDevice(config)
    return when (config.cameraType) {
        CameraType.PI -> {
            log.info("camera.type=pi — Pi Camera Module (rpicam-still) on camera {}.", piCameraIndex(config))
            LibcameraCamera(cameraIndex = piCameraIndex(config))
        }
        CameraType.WEBCAM -> {
            log.info("camera.type=webcam — imagesnap, {}.", describeWebcam(webcamDevice))
            ImagesnapCamera(device = webcamDevice)
        }
        CameraType.PLACEHOLDER -> {
            log.info("camera.type=placeholder — using the synthetic placeholder photo.")
            PlaceholderCamera()
        }
        CameraType.AUTO -> when {
            isLinux -> {
                log.info("camera.type=auto on Linux — Pi Camera Module (rpicam-still) on camera {}.", piCameraIndex(config))
                LibcameraCamera(cameraIndex = piCameraIndex(config))
            }
            ImagesnapCamera.isAvailable() -> {
                log.info("camera.type=auto — local webcam via imagesnap, {}.", describeWebcam(webcamDevice))
                ImagesnapCamera(device = webcamDevice)
            }
            else -> {
                log.warn(
                    "Not running on Linux and `imagesnap` isn't installed — using a synthetic placeholder photo. " +
                        "Run `brew install imagesnap` to capture from this Mac's webcam instead, " +
                        "or set camera.type explicitly in the event's photobooth.properties."
                )
                PlaceholderCamera()
            }
        }
    }
}

/**
 * Which Pi camera to open. Defaults to `0` rather than leaving `--camera`
 * off entirely: an unset index lets libcamera pick, and that isn't stable
 * when several cameras are attached. Set `camera.device` to override.
 */
private fun piCameraIndex(config: AppConfig): Int =
    config.cameraDevice?.toIntOrNull() ?: run {
        if (!config.cameraDevice.isNullOrBlank()) {
            log.warn("camera.device='{}' isn't a numeric camera index — using camera 0.", config.cameraDevice)
        }
        0
    }

/** The `imagesnap -d` device name, or null to let macOS pick its default camera. */
private fun webcamDevice(config: AppConfig): String? = config.cameraDevice?.ifBlank { null }

private fun describeWebcam(device: String?): String =
    device?.let { "device '$it'" }
        ?: "the system default camera (set camera.device to a name from `imagesnap -l` to pin it)"

/**
 * Picks how the self-view gets its frames. A real continuous stream is used
 * only when the matching camera is in play: [RpicamPreviewStream] with the Pi
 * Camera (`camera.type=pi`, or `auto` on Linux), [FfmpegPreviewStream] on
 * macOS with `ffmpeg`. Otherwise [PreviewLoop]'s periodic-still refresh.
 */
private fun selectPreviewSource(config: AppConfig, camera: Camera): PreviewSource {
    val usingPiCamera = config.cameraType == CameraType.PI ||
        (config.cameraType == CameraType.AUTO && isLinux)
    return when {
        // Same camera the shutter uses, so the self-view and the photo match.
        usingPiCamera -> RpicamPreviewStream(cameraIndex = piCameraIndex(config))
        !isLinux && FfmpegPreviewStream.isAvailable() -> FfmpegPreviewStream(device = webcamDevice(config) ?: "0")
        else -> {
            log.warn(
                "No live preview stream for this camera/OS — the self-view will be a periodic still " +
                    "refresh (preview.interval.ms), not smooth video. On macOS, `brew install ffmpeg` " +
                    "for a real live preview during development."
            )
            PreviewLoop(camera, config.previewIntervalMillis, File(config.dataDir, "preview.jpg"))
        }
    }
}

/** Real Pi4J GPIO on Linux; a logging no-op elsewhere so the app still runs on a dev machine. */
private fun selectGpio(config: AppConfig): Gpio = if (isLinux) {
    try {
        Pi4jGpioController(config.buttonGpioPin, config.ledGpioPin)
    } catch (e: Exception) {
        log.warn("Pi4J GPIO init failed ({}) — falling back to a simulated controller.", e.message)
        SimulatedGpioController()
    }
} else {
    SimulatedGpioController()
}
