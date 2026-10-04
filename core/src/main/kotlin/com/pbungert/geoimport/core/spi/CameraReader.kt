package com.pbungert.geoimport.core.spi

import com.pbungert.geoimport.core.imports.Camera
import java.io.File

/**
 * Reads which camera took a photo, from EXIF Make, Model and BodySerialNumber.
 *
 * Returns null when the file has no EXIF to say - a video, or a format the
 * reader does not understand - which leaves the caller to try another file.
 */
fun interface CameraReader {
    fun readCamera(file: File): Camera?
}
