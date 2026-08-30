package com.photobooth.camera.impl

import com.photobooth.camera.Camera
import java.io.File

/**
 * Serializes access to [delegate] so a preview-loop capture ([PreviewLoop])
 * and the real shutter capture never hit the camera device at the same
 * time — most backends here (`rpicam-still`, `imagesnap`) are single-shot
 * CLI processes that don't support concurrent use.
 */
class SynchronizedCamera(private val delegate: Camera) : Camera {
    private val lock = Any()

    override fun capture(outputFile: File): File = synchronized(lock) {
        delegate.capture(outputFile)
    }
}
