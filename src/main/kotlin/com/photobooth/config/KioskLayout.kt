package com.photobooth.config

import java.util.Properties

/**
 * Where the idle screen's fixed elements sit: the two QR codes and the
 * "tap to start" hint. Per event via `kiosk.*.position` keys, no rebuild.
 * Each value is a [DecorPosition] (`top-left` … `bottom-right`, plus
 * `center-left` / `center-right`). Styling of these elements is in
 * `kiosk/kiosk.css`; this is only *where* they go.
 */
data class KioskLayout(
    /** The "scan to choose your layout" QR — shown only when overlay selection is on. */
    val layoutQr: DecorPosition,
    /** The "missed your QR?" recovery QR (`/last`). */
    val recoveryQr: DecorPosition,
    /** The "Tap to start" call to action. */
    val tapHint: DecorPosition,
) {
    companion object {
        val DEFAULT = KioskLayout(
            layoutQr = DecorPosition.CENTER_LEFT,
            recoveryQr = DecorPosition.CENTER_RIGHT,
            tapHint = DecorPosition.BOTTOM_CENTER,
        )

        fun from(props: Properties): KioskLayout {
            fun pos(key: String, default: DecorPosition) =
                props.getProperty(key, "").trim().ifBlank { null }?.let(DecorPosition::parse) ?: default
            return KioskLayout(
                layoutQr = pos("kiosk.qr.layout.position", DEFAULT.layoutQr),
                recoveryQr = pos("kiosk.qr.recovery.position", DEFAULT.recoveryQr),
                tapHint = pos("kiosk.taphint.position", DEFAULT.tapHint),
            )
        }
    }
}
