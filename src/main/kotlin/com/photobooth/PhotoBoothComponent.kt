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
import com.photobooth.gpio.Gpio
import com.photobooth.gpio.impl.Pi4jGpioController
import com.photobooth.gpio.impl.SimulatedGpioController
import com.photobooth.i18n.Strings
import com.photobooth.imaging.OverlayCompositor
import com.photobooth.qrcode.QrCodeGenerator
import com.photobooth.server.CloudflareTunnel
import com.photobooth.server.DownloadServer
import com.photobooth.server.PhonePages
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

    override fun close() {
        runCatching { previewSource.stop() }
        downloadServer.stop()
        tunnel.stop()
        gpio.close()
    }

    companion object {
        /**
         * Builds the full non-UI graph. Blocks while the Cloudflare tunnel
         * comes up (up to ~20s) — call this off the JavaFX thread. Throws if
         * `cloudflared` never reports a public URL.
         */
        fun build(
            config: AppConfig = AppConfig.load(),
            strings: Strings = Strings.load(config.language),
        ): PhotoBoothComponent {
            val camera = SynchronizedCamera(selectCamera(config))
            val gpio = selectGpio(config)
            val compositor = OverlayCompositor()
            val qr = QrCodeGenerator()
            val overlaySelection = if (config.overlaySelectionEnabled) {
                OverlaySelection(config.overlaysDir, config.overlayFile)
            } else null

            val lastPhotoFileName = AtomicReference<String?>(null)
            val phonePages = PhonePages(brandName = config.webBrandName, lang = config.language)
            val downloadServer = DownloadServer(
                config.serverPort,
                config.mergedDir,
                overlaySelection,
                strings,
                phonePages,
                lastPhotoFileName,
            ).apply { start() }

            val tunnel = CloudflareTunnel(config.serverPort)
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

            return PhotoBoothComponent(
                config = config,
                strings = strings,
                gpio = gpio,
                session = session,
                previewSource = selectPreviewSource(config, camera),
                overlaySelection = overlaySelection,
                chooseOverlayQr = chooseOverlayQr,
                lastPhotoQr = lastPhotoQr,
                downloadServer = downloadServer,
                tunnel = tunnel,
            )
        }
    }
}

/**
 * Picks the [Camera] per `camera.type`. `auto` (default) detects: the real Pi
 * camera on Linux, otherwise a Mac webcam via `imagesnap` if installed, else
 * a synthetic placeholder.
 */
private fun selectCamera(config: AppConfig): Camera {
    // pi/rpicam wants a numeric --camera index; imagesnap wants a -d device name.
    val piIndex = config.cameraDevice?.let {
        it.toIntOrNull() ?: run { log.warn("camera.device='{}' isn't a camera index — ignoring for the Pi camera.", it); null }
    }
    return when (config.cameraType) {
        CameraType.PI -> {
            log.info("camera.type=pi — Pi Camera Module (rpicam-still){}.", config.cameraDevice?.let { " camera $it" } ?: "")
            LibcameraCamera(cameraIndex = piIndex)
        }
        CameraType.WEBCAM -> {
            log.info("camera.type=webcam — local webcam via imagesnap{}.", config.cameraDevice?.let { " '$it'" } ?: "")
            ImagesnapCamera(device = config.cameraDevice)
        }
        CameraType.PLACEHOLDER -> {
            log.info("camera.type=placeholder — using the synthetic placeholder photo.")
            PlaceholderCamera()
        }
        CameraType.AUTO -> when {
            isLinux -> LibcameraCamera(cameraIndex = piIndex)
            ImagesnapCamera.isAvailable() -> {
                log.info("Not running on Linux — capturing from the local webcam via imagesnap.")
                ImagesnapCamera(device = config.cameraDevice)
            }
            else -> {
                log.warn(
                    "Not running on Linux and `imagesnap` isn't installed — using a synthetic placeholder photo. " +
                        "Run `brew install imagesnap` to capture from this Mac's webcam instead, " +
                        "or set camera.type explicitly in config/photobooth.properties."
                )
                PlaceholderCamera()
            }
        }
    }
}

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
        usingPiCamera -> RpicamPreviewStream(cameraIndex = config.cameraDevice?.toIntOrNull())
        !isLinux && FfmpegPreviewStream.isAvailable() -> FfmpegPreviewStream(device = config.cameraDevice ?: "0")
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
