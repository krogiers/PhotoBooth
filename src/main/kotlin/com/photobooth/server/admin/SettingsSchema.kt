package com.photobooth.server.admin

/**
 * The scalar `photobooth.properties` keys the admin panel can edit, with
 * enough metadata to render a typed form, validate a submission, and tell the
 * user whether a change takes effect on "reload booth UI" or needs a full
 * restart.
 *
 * The numbered `decor.<n>.*` image-layer keys are **not** here — they're a
 * list, handled by a dedicated editor in a later phase — but a save preserves
 * them untouched (see `SettingsRoutes`).
 */
internal enum class SettingType { BOOL, INT, LONG, STRING, ENUM }

internal data class SettingSpec(
    val key: String,
    val group: String,
    val type: SettingType,
    val default: String,
    val enumValues: List<String> = emptyList(),
    /** Value hidden in read views and left unchanged on save when the field is submitted blank. */
    val secret: Boolean = false,
    /** true = "reload booth UI" applies it; false = needs a full booth restart. */
    val live: Boolean = false,
    val help: String = "",
) {
    /** null when [raw] is a valid value for this spec, else a human-readable reason. */
    fun validate(raw: String): String? {
        val v = raw.trim()
        return when (type) {
            SettingType.BOOL -> if (v.isEmpty() || v.equals("true", true) || v.equals("false", true)) null
            else "must be true or false"
            SettingType.INT -> when {
                v.isEmpty() -> null
                v.toIntOrNull() == null -> "must be a whole number"
                else -> null
            }
            SettingType.LONG -> when {
                v.isEmpty() -> null
                v.toLongOrNull() == null -> "must be a whole number"
                else -> null
            }
            SettingType.ENUM -> if (v.isEmpty() || enumValues.any { it.equals(v, true) }) null
            else "must be one of: ${enumValues.joinToString(", ")}"
            SettingType.STRING -> null
        }
    }
}

internal object SettingsSchema {

    private val POSITIONS = listOf(
        "top-left", "top-center", "top-right",
        "center-left", "center", "center-right",
        "bottom-left", "bottom-center", "bottom-right",
    )

    val SPECS: List<SettingSpec> = listOf(
        // --- Camera -----------------------------------------------------------
        SettingSpec("camera.type", "Camera", SettingType.ENUM, "auto",
            enumValues = listOf("auto", "pi", "webcam", "placeholder"),
            help = "Which capture backend. auto = Pi camera on Linux, else a Mac webcam, else a placeholder."),
        SettingSpec("camera.device", "Camera", SettingType.STRING, "",
            help = "Which camera when several are attached. Pi: an index (blank = 0). Webcam: a device name from `imagesnap -l`."),
        SettingSpec("preview.interval.ms", "Camera", SettingType.LONG, "1000",
            help = "Still-grab interval for the self-view when there's no live stream."),

        // --- GPIO -----------------------------------------------------------
        SettingSpec("gpio.button.pin", "GPIO", SettingType.INT, "17", help = "BCM pin for the arcade start button."),
        SettingSpec("gpio.led.pin", "GPIO", SettingType.INT, "27", help = "BCM pin for the \"lighting ok\" LED."),

        // --- Timing -------------------------------------------------------
        SettingSpec("countdown.seconds", "Timing", SettingType.INT, "3", live = true,
            help = "Length of the 3-2-1 countdown."),
        SettingSpec("smile.hold.ms", "Timing", SettingType.LONG, "1200", live = true,
            help = "Minimum time \"Smile!\" stays up after the countdown, in milliseconds."),
        SettingSpec("result.display.seconds", "Timing", SettingType.INT, "15", live = true,
            help = "How long the photo + download QR stay on screen before the idle screen returns."),

        // --- Screens ------------------------------------------------------
        SettingSpec("secondscreen.enabled", "Screens", SettingType.BOOL, "false",
            help = "A second operator screen: the layout gallery + a red shoot button. Needs overlay.selection.enabled."),
        SettingSpec("screen.booth.display", "Screens", SettingType.INT, "0", help = "Display index for the booth screen (0 = first)."),
        SettingSpec("screen.picker.display", "Screens", SettingType.INT, "1",
            help = "Display index for the picker screen. Missing display = no picker (no fallback)."),
        SettingSpec("screen.booth.fullscreen", "Screens", SettingType.BOOL, "true", help = "Booth screen fills its display."),
        SettingSpec("screen.picker.fullscreen", "Screens", SettingType.BOOL, "true", help = "Picker screen fills its display."),
        SettingSpec("window.width", "Screens", SettingType.INT, "", help = "Window width when not fullscreen. Blank = ~80% of the display."),
        SettingSpec("window.height", "Screens", SettingType.INT, "", help = "Window height when not fullscreen. Blank = ~80% of the display."),

        // --- Idle screen (live) -----------------------------------------
        SettingSpec("kiosk.qr.layout.enabled", "Idle screen", SettingType.BOOL, "true", live = true,
            help = "Show the \"choose your layout\" QR."),
        SettingSpec("kiosk.qr.layout.position", "Idle screen", SettingType.ENUM, "bottom-left", enumValues = POSITIONS, live = true,
            help = "Where the layout QR sits."),
        SettingSpec("kiosk.qr.recovery.enabled", "Idle screen", SettingType.BOOL, "true", live = true,
            help = "Show the \"missed your QR?\" recovery QR."),
        SettingSpec("kiosk.qr.recovery.position", "Idle screen", SettingType.ENUM, "bottom-right", enumValues = POSITIONS, live = true,
            help = "Where the recovery QR sits."),
        SettingSpec("kiosk.taphint.position", "Idle screen", SettingType.ENUM, "bottom-center", enumValues = POSITIONS, live = true,
            help = "Where \"Tap to start\" sits."),

        // --- Decor title (live) ---------------------------------------
        SettingSpec("decor.title.text", "Decor title", SettingType.STRING, "", live = true,
            help = "A text title on the idle screen. Blank = use a decor image instead."),
        SettingSpec("decor.title.font", "Decor title", SettingType.STRING, "", live = true,
            help = "A .ttf/.otf in the bundle for the title, e.g. decor/title.ttf. Blank = default font."),
        SettingSpec("decor.title.size", "Decor title", SettingType.LONG, "64", live = true, help = "Title font size."),
        SettingSpec("decor.title.color", "Decor title", SettingType.STRING, "#ffffff", live = true, help = "Title colour, #rrggbb."),
        SettingSpec("decor.title.position", "Decor title", SettingType.ENUM, "top-center", enumValues = POSITIONS, live = true,
            help = "Where the title sits."),

        // --- Overlay --------------------------------------------------
        SettingSpec("overlay.file", "Overlay", SettingType.STRING, "overlay.png", live = true,
            help = "Default overlay merged onto every photo, relative to the bundle. Set it on the Overlays page."),
        SettingSpec("overlay.selection.enabled", "Overlay", SettingType.BOOL, "false",
            help = "Let guests pick an overlay on their phone before posing."),
        SettingSpec("overlays.dir", "Overlay", SettingType.STRING, "overlays",
            help = "Folder of selectable overlay PNGs, relative to the bundle."),

        // --- Text / branding ----------------------------------------
        SettingSpec("ui.language", "Text", SettingType.STRING, "en",
            help = "Guest-facing language code. Built in: en, nl. Others need an i18n/<code>.properties in the bundle."),
        SettingSpec("web.brand.name", "Text", SettingType.STRING, "",
            help = "Optional wordmark at the top of the phone pages."),

        // --- Storage -----------------------------------------------
        SettingSpec("data.dir", "Storage", SettingType.STRING, "data", help = "Where photos are written, relative to the bundle."),
        SettingSpec("data.subdir", "Storage", SettingType.STRING, "", help = "Sub-folder name; blank = today's date."),

        // --- Server / remote start -------------------------------
        SettingSpec("server.port", "Server", SettingType.INT, "8080", help = "Port the guest download server listens on (tunnelled)."),
        SettingSpec("remote_start.enabled", "Server", SettingType.BOOL, "true",
            help = "Show a \"Take the photo\" button on the phone's layout page."),

        // --- Admin panel --------------------------------------
        SettingSpec("admin.enabled", "Admin panel", SettingType.BOOL, "false", help = "Run this panel. Needs admin.password."),
        SettingSpec("admin.port", "Admin panel", SettingType.INT, "8081", help = "Port for this panel. Never tunnelled."),
        SettingSpec("admin.password", "Admin panel", SettingType.STRING, "", secret = true,
            help = "Password for user \"admin\". Leave the field blank on save to keep the current one."),
    )

    val BY_KEY: Map<String, SettingSpec> = SPECS.associateBy { it.key }

    val GROUPS: List<String> = SPECS.map { it.group }.distinct()

    fun forGroup(group: String) = SPECS.filter { it.group == group }
}
