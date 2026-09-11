package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import java.io.File

/** Where a position ended up. */
sealed interface GpsWriteResult {
    /** Tags written into the media file itself. */
    data class Embedded(val file: File) : GpsWriteResult

    /** Position written to a sidecar beside the media file. */
    data class Sidecar(val sidecar: File) : GpsWriteResult
}

/**
 * Writes a position into (or beside) a media file.
 *
 * Implementations are chained by [FallbackGpsWriter] so the desktop can prefer
 * exiftool where it is available while both platforms share the same built-in
 * path, and so a failed embedded write always degrades to a sidecar rather
 * than losing the position.
 */
interface GpsWriter {

    /** Shown in dry-run plans and logs, e.g. "built-in" or "exiftool". */
    val name: String

    fun supports(file: File): Boolean

    fun write(file: File, point: TrackPoint): GpsWriteResult
}
