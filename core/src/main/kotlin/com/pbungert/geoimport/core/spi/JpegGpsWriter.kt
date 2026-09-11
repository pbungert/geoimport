package com.pbungert.geoimport.core.spi

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File

/**
 * Writes GPS tags into an existing JPEG, in place.
 *
 * The only part of GPS writing that has no portable implementation: Android
 * uses androidx.exifinterface, the desktop uses Apache Commons Imaging. Every
 * container-level concern — including the RAF header surgery in
 * [com.pbungert.geoimport.core.geotag.RafGpsWriter] — is shared and sits on
 * top of this.
 */
fun interface JpegGpsWriter {
    fun writeGps(jpeg: File, point: TrackPoint)
}
