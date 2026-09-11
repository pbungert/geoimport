package com.pbungert.geoimport.core.spi

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Reads EXIF DateTimeOriginal (0x9003) and OffsetTimeOriginal (0x9011).
 *
 * Returns null when the file has no parseable capture time, which leaves the
 * caller to fall back to file timestamps. The offset is null when the camera
 * did not record one — the common case, and the reason
 * [com.pbungert.geoimport.core.imports.CaptureTimeResolver] needs an assumed
 * zone rather than silently using the machine's.
 */
fun interface ExifDateReader {
    fun readDateTaken(file: File): Pair<LocalDateTime, ZoneOffset?>?
}
