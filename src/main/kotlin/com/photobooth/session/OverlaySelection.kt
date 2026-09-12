package com.photobooth.session

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds which overlay the next shoot should use, chosen by a guest on their
 * phone (see the `/choose` route in [com.photobooth.server.DownloadServer])
 * before they pose. There's a single slot, not one per guest — this booth
 * takes one group's photo at a time, same as the physical arcade button.
 *
 * [current] falls back to [defaultOverlay] until someone picks one, and
 * [reset] is called after each shoot so a stale choice doesn't carry over to
 * the next group.
 */
class OverlaySelection(
    val overlaysDir: File,
    /**
     * The overlay used when no guest has picked one. Re-assignable so the
     * admin panel's "set as default" applies without a restart (the kiosk
     * reload also refreshes the self-view preview through it).
     */
    @Volatile var defaultOverlay: File,
) {
    private val selectedFileName = AtomicReference<String?>(null)

    /** Fires with the new [current] overlay whenever [choose] or [reset] actually changes it — see [com.photobooth.ui.BoothView.updateOverlayPreview]. */
    var onChanged: (File) -> Unit = {}

    /** The overlay files guests can currently choose from — just the `.png` files directly under [overlaysDir]. */
    fun available(): List<File> =
        overlaysDir.listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Records [fileName] as the choice for the next shoot. Must name a file
     * directly under [overlaysDir] — anything else (including a "../" escape
     * attempt, since this is reachable from a guest's phone) is rejected.
     */
    fun choose(fileName: String): Boolean {
        val file = File(overlaysDir, fileName).normalize()
        if (!file.startsWith(overlaysDir) || !file.isFile) return false
        selectedFileName.set(file.name)
        onChanged(file)
        return true
    }

    /** The overlay to merge into the next photo: the guest's pick, or [defaultOverlay] if none was made. */
    fun current(): File = selectedFileName.get()?.let { File(overlaysDir, it) } ?: defaultOverlay

    /** Clears the pick after a shoot completes, so the next group starts from [defaultOverlay] again. */
    fun reset() {
        if (selectedFileName.getAndSet(null) != null) onChanged(defaultOverlay)
    }
}
