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
 * Central configuration for one event. Reads `<eventDir>/photobooth.properties`
 * ([EventBundle.propertiesFile]) if present, falling back to sane defaults so
 * the app runs on a dev machine with no GPIO/camera hardware and no bundle at
 * all. Path-valued keys (`overlay.file`, `overlays.dir`, `data.dir`,
 * `decor.<n>.file`) resolve **relative to the event bundle**.
 */
data class AppConfig(
    /** The event bundle this config was loaded from — see [EventBundle]. */
    val eventDir: File,
    /** The active event's id (`events/<id>`). */
    val eventId: String,
    val cameraType: CameraType,
    /**
     * Which physical camera to use when the machine has more than one.
     * `pi` / Linux `auto`: a camera **index** (`--camera <n>`, see
     * `rpicam-still --list-cameras`) — blank means camera `0`, **not** "let
     * libcamera decide", so the same camera is used every boot. `webcam`
     * (macOS): a device **name** (`-d "<name>"`, see `imagesnap -l`); blank
     * there is macOS's default camera, which can move between runs — set it
     * to pin one.
     */
    val cameraDevice: String?,
    val buttonGpioPin: Int,
    val ledGpioPin: Int,
    val countdownSeconds: Int,
    /** How long the merged photo + download QR stay on screen before the idle screen returns. */
    val resultDisplaySeconds: Int,
    /** Minimum time "Smile!" stays up after the countdown before the result screen appears (`smile.hold.ms`). */
    val smileHoldMillis: Long,
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
     * Whether the booth screen fills its display — a borderless window sized
     * to the monitor's own resolution (`screen.booth.fullscreen`, default
     * `true`). `false` gives a normal resizable window ([windowWidth] ×
     * [windowHeight]). The `PHOTOBOOTH_WINDOWED` env var forces windowed for
     * both screens regardless.
     */
    val boothFullscreen: Boolean,
    /** Same as [boothFullscreen] for the picker screen (`screen.picker.fullscreen`, default `true`). */
    val pickerFullscreen: Boolean,
    /**
     * Window size (px) for a screen whose `*.fullscreen` is `false` (a dev
     * run). **Blank/unset (null) means auto** — the app opens the window at
     * ~80% of whatever display it lands on, so the user never has to look up
     * a resolution. A fullscreen screen ignores this entirely and uses the
     * display's own size.
     */
    val windowWidth: Int?,
    val windowHeight: Int?,
    /**
     * Whether the LAN admin panel runs (`admin.enabled`, default `false`). It
     * listens on [adminPort] — a **separate** port from [serverPort], so only
     * the guest port is ever fronted by the Cloudflare tunnel. Needs a
     * non-blank [adminPassword] or it stays off.
     */
    val adminEnabled: Boolean,
    /** Port the admin panel listens on (`admin.port`, default `8081`). Never tunnelled. */
    val adminPort: Int,
    /** Admin-panel password (`admin.password`); blank/null keeps the panel off even with [adminEnabled]. */
    val adminPassword: String?,
) {
    val originalsDir: File get() = File(sessionDir, "originals")
    val mergedDir: File get() = File(sessionDir, "merged")

    companion object {
        /** Convenience for the composition root / previews — loads the currently-active event bundle. */
        fun load(today: LocalDate = LocalDate.now()): AppConfig = load(EventStore().active(), today)

        fun load(bundle: EventBundle, today: LocalDate = LocalDate.now()): AppConfig {
            val props = Properties()
            bundle.propertiesFile.takeIf { it.isFile }?.inputStream()?.use { props.load(it) }

            fun prop(key: String, default: String) = props.getProperty(key, default)

            val dataDir = bundle.resolve(prop("data.dir", "data"))
            val sessionDir = File(dataDir, prop("data.subdir", "").ifBlank { today.toString() })
            val config = AppConfig(
                eventDir = bundle.dir,
                eventId = bundle.id,
                cameraType = CameraType.parse(prop("camera.type", "auto")),
                cameraDevice = prop("camera.device", "").ifBlank { null },
                buttonGpioPin = prop("gpio.button.pin", "17").toInt(),
                ledGpioPin = prop("gpio.led.pin", "27").toInt(),
                countdownSeconds = prop("countdown.seconds", "3").toInt(),
                resultDisplaySeconds = prop("result.display.seconds", "15").toInt(),
                smileHoldMillis = prop("smile.hold.ms", "1200").toLong(),
                previewIntervalMillis = prop("preview.interval.ms", "1000").toLong(),
                serverPort = prop("server.port", "8080").toInt(),
                dataDir = dataDir,
                sessionDir = sessionDir,
                overlayFile = bundle.resolve(prop("overlay.file", "overlay.png")),
                overlaySelectionEnabled = prop("overlay.selection.enabled", "false").toBoolean(),
                overlaysDir = bundle.resolve(prop("overlays.dir", "overlays")),
                language = prop("ui.language", "en"),
                remoteStartEnabled = prop("remote_start.enabled", "true").toBoolean(),
                webBrandName = prop("web.brand.name", "").ifBlank { null },
                decor = DecorConfig.from(props, bundle.dir),
                kioskLayout = KioskLayout.from(props),
                secondScreenEnabled = prop("secondscreen.enabled", "false").toBoolean(),
                boothDisplay = prop("screen.booth.display", "0").toInt(),
                pickerDisplay = prop("screen.picker.display", "1").toInt(),
                boothFullscreen = prop("screen.booth.fullscreen", "true").toBoolean(),
                pickerFullscreen = prop("screen.picker.fullscreen", "true").toBoolean(),
                windowWidth = prop("window.width", "").trim().toIntOrNull(),
                windowHeight = prop("window.height", "").trim().toIntOrNull(),
                adminEnabled = prop("admin.enabled", "false").toBoolean(),
                adminPort = prop("admin.port", "8081").toInt(),
                adminPassword = prop("admin.password", "").ifBlank { null },
            )
            listOf(config.originalsDir, config.mergedDir).forEach { it.mkdirs() }
            return config
        }
    }
}
