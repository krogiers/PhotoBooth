package com.photobooth.camera

import java.io.File

/** A device that can take a single still photo and save it to a file. */
interface Camera {
    fun capture(outputFile: File): File
}
