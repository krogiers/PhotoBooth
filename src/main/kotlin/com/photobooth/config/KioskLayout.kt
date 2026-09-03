package com.photobooth.config

import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * The idle screen's fixed elements: the two QR codes and the "tap to start"
 * hint — whether each shows (`kiosk.*.enabled`) and where it sits
 * (`kiosk.*.position`). Per event, no rebuild. Position is a [DecorPosition]
 * (`top-left` … `bottom-right`, plus `center-left` / `center-right`). Styling
 * is in `kiosk/kiosk.css`; this is only *whether* and *where*.
 */
data class KioskLayout(
    /** The "scan to choose your layout" QR — also needs `overlay.selection.enabled` to have anything to point at. */
    val layoutQrEnabled: Boolean,
    val layoutQr: DecorPosition,
    /** The "missed your QR?" recovery QR (`/last`). */
    val recoveryQrEnabled: Boolean,
    val recoveryQr: DecorPosition,
    /** The "Tap to start" call to action. */
    val tapHint: DecorPosition,
) {
    companion object {
        private val log = LoggerFactory.getLogger(KioskLayout::class.java)

        val DEFAULT = KioskLayout(
            layoutQrEnabled = true,
            layoutQr = DecorPosition.BOTTOM_LEFT,
            recoveryQrEnabled = true,
            recoveryQr = DecorPosition.BOTTOM_RIGHT,
            tapHint = DecorPosition.BOTTOM_CENTER,
        )

        fun from(props: Properties): KioskLayout {
            fun flag(key: String, default: Boolean) =
                props.getProperty(key, default.toString()).trim().toBoolean()

            // A typo in one position key falls back to that element's default with a
            // warning — it must not take the whole booth to the error screen.
            fun pos(key: String, default: DecorPosition) =
                props.getProperty(key, "").trim().ifBlank { null }?.let { raw ->
                    runCatching { DecorPosition.parse(raw) }
                        .onFailure { log.warn("{}: {} — using {}", key, it.message, default.name.lowercase().replace('_', '-')) }
                        .getOrNull()
                } ?: default

            return KioskLayout(
                layoutQrEnabled = flag("kiosk.qr.layout.enabled", DEFAULT.layoutQrEnabled),
                layoutQr = pos("kiosk.qr.layout.position", DEFAULT.layoutQr),
                recoveryQrEnabled = flag("kiosk.qr.recovery.enabled", DEFAULT.recoveryQrEnabled),
                recoveryQr = pos("kiosk.qr.recovery.position", DEFAULT.recoveryQr),
                tapHint = pos("kiosk.taphint.position", DEFAULT.tapHint),
            )
        }
    }
}
