package com.photobooth.config

import java.io.File
import java.time.LocalDate
import java.util.Properties

/**
 * Which [com.photobooth.camera.Camera] implementation to use. [AUTO] picks
 * the Pi camera on Linux and, on any other OS, a Mac webcam via `imagesnap`
 * if installed, else the synthetic placeholder — see
 * [com.photobooth.selectCamera]. Set `camera.type` to force one explicitly,
 * e.g. to test the placeholder on a Pi, or `webcam` on a Mac that also has
 * `imagesnap` installed but you want the placeholder for a quick run.
 */
enum class CameraType {
    AUTO, PI, WEBCAM, PLACEHOLDER;

    companion object {
        fun parse(value: String): CameraType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("Unknown camera.type '$value' — expected one of ${entries.joinToString { it.name.lowercase() }}")
    }
}

/**
 * Central configuration. Reads `config/photobooth.properties` next to the
 * application if present, falling back to sane defaults so the app can be
 * launched on a dev machine without any GPIO/camera hardware attached.
 */
data class AppConfig(
    val cameraType: CameraType,
    /**
     * Which physical camera to use when the backend has more than one.
     * `pi` / Linux `auto`: a camera **index** (`0`, `1`, … — `--camera <n>`,
     * see `rpicam-still --list-cameras`). `webcam`: a device **name**
     * (`-d "<name>"`, see `imagesnap -l`) or index. Blank = the backend's
     * default.
     */
    val cameraDevice: String?,
    val buttonGpioPin: Int,
    val ledGpioPin: Int,
    val countdownSeconds: Int,
    /** How long the merged photo + download QR stay on screen before the idle screen returns. */
    val resultDisplaySeconds: Int,
    /** How often the idle-screen self-view grabs a fresh frame from the camera. */
    val previewIntervalMillis: Long,
    /**
     * Port the local [com.photobooth.server.DownloadServer] listens on. Guests
     * never hit this directly — a Cloudflare quick tunnel
     * ([com.photobooth.server.CloudflareTunnel]) fronts it on the public
     * internet so a guest's phone reaches their photo over its own mobile data.
     */
    val serverPort: Int,
    val dataDir: File,
    /**
     * Per-session folder under [dataDir] where this run's photos are written —
     * the ISO date (`2026-08-30`) by default, or `data.subdir` if set. Keeps
     * back-to-back events' photos in separate folders even when `data/` is
     * never cleaned out.
     */
    val sessionDir: File,
    val overlayFile: File,
    /** Whether guests pick their overlay on their phone before posing — see [com.photobooth.session.OverlaySelection]. */
    val overlaySelectionEnabled: Boolean,
    /** Folder of selectable overlay PNGs, used only when [overlaySelectionEnabled]. */
    val overlaysDir: File,
    /**
     * Guest-facing text language code (e.g. `en`, `nl`), for both the kiosk
     * screens and the phone-facing web pages — see [com.photobooth.i18n.Strings].
     * Not validated here: [com.photobooth.i18n.Strings.load] is what knows
     * which codes actually resolve to a translation, bundled or from
     * `config/i18n/`.
     */
    val language: String,
    /** Whether the phone layout page shows a "Take the photo" button that starts a shoot via `/shoot` — see [com.photobooth.server.DownloadServer]. */
    val remoteStartEnabled: Boolean,
    /**
     * Optional wordmark shown on the phone-facing pages next to
     * `config/web/logo.*` (if present). Null/blank hides the brand bar. See
     * [com.photobooth.server.PhonePages].
     */
    val webBrandName: String?,
    /** Per-event dressing for the JavaFX idle screen — see [DecorConfig]. [DecorConfig.NONE] when nothing is set. */
    val decor: DecorConfig,
    /** Where the idle screen's QR codes and "tap to start" hint sit — see [KioskLayout]. */
    val kioskLayout: KioskLayout,
    /**
     * A second display showing the layout gallery + a red "take the photo"
     * button (no download) — for an operator standing beside the booth. Needs
     * `overlay.selection.enabled=true`. `false` = booth screen only.
     */
    val secondScreenEnabled: Boolean,
    /**
     * Display index (0 = first / usually primary) for the main booth screen.
     * If that display isn't present the booth still runs, on the primary,
     * with a warning.
     */
    val boothDisplay: Int,
    /**
     * Display index for the picker screen. It appears **only** there — if
     * that display isn't present, there is simply no picker screen (no
     * fallback).
     */
    val pickerDisplay: Int,
    /**
     * Whether the kiosk window goes full-screen (`window.fullscreen`, default
     * `true`). The `PHOTOBOOTH_WINDOWED` env var still forces windowed for a
     * quick dev run regardless of this.
     */
    val windowFullscreen: Boolean,
    /**
     * Initial window size (px). Only visible when not full-screen
     * (`window.fullscreen=false` or `PHOTOBOOTH_WINDOWED` set); a full-screen
     * kiosk uses the display's own size and the self-view scales to fill it.
     */
    val windowWidth: Int,
    val windowHeight: Int,
) {
    val originalsDir: File get() = File(sessionDir, "originals")
    val mergedDir: File get() = File(sessionDir, "merged")

    companion object {
        fun load(
            file: File = File("config/photobooth.properties"),
            today: LocalDate = LocalDate.now(),
        ): AppConfig {
            val props = Properties()
            if (file.exists()) {
                file.inputStream().use { props.load(it) }
            }

            fun prop(key: String, default: String) = props.getProperty(key, default)

            val dataDir = File(prop("data.dir", "data"))
            val sessionDir = File(dataDir, prop("data.subdir", "").ifBlank { today.toString() })
            val config = AppConfig(
                cameraType = CameraType.parse(prop("camera.type", "auto")),
                cameraDevice = prop("camera.device", "").ifBlank { null },
                buttonGpioPin = prop("gpio.button.pin", "17").toInt(),
                ledGpioPin = prop("gpio.led.pin", "27").toInt(),
                countdownSeconds = prop("countdown.seconds", "3").toInt(),
                resultDisplaySeconds = prop("result.display.seconds", "15").toInt(),
                previewIntervalMillis = prop("preview.interval.ms", "1000").toLong(),
                serverPort = prop("server.port", "8080").toInt(),
                dataDir = dataDir,
                sessionDir = sessionDir,
                overlayFile = File(prop("overlay.file", "config/overlay.png")),
                overlaySelectionEnabled = prop("overlay.selection.enabled", "false").toBoolean(),
                overlaysDir = File(prop("overlays.dir", "config/overlays")),
                language = prop("ui.language", "en"),
                remoteStartEnabled = prop("remote_start.enabled", "true").toBoolean(),
                webBrandName = prop("web.brand.name", "").ifBlank { null },
                decor = DecorConfig.from(props),
                kioskLayout = KioskLayout.from(props),
                secondScreenEnabled = prop("secondscreen.enabled", "false").toBoolean(),
                boothDisplay = prop("screen.booth.display", "0").toInt(),
                pickerDisplay = prop("screen.picker.display", "1").toInt(),
                windowFullscreen = prop("window.fullscreen", "true").toBoolean(),
                windowWidth = prop("window.width", "1024").toInt(),
                windowHeight = prop("window.height", "768").toInt(),
            )
            listOf(config.originalsDir, config.mergedDir).forEach { it.mkdirs() }
            return config
        }
    }
}
