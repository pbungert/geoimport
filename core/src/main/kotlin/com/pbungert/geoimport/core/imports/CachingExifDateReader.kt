package com.pbungert.geoimport.core.imports

import com.pbungert.geoimport.core.spi.ExifDateReader
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers what [delegate] read, per file, for as long as the file is
 * unchanged.
 *
 * Reading EXIF off a card is the slow part of a preview, and a preview is
 * rebuilt every time a setting changes. None of the settings change what the
 * camera wrote - the zone and clock offset are applied afterwards, by
 * [CaptureTimeResolver] - so the second build needs no reads at all. A file
 * whose size or modification time differs is read again, which covers a card
 * swapped for another with the same file names.
 *
 * Safe to use from several threads at once.
 */
class CachingExifDateReader(private val delegate: ExifDateReader) : ExifDateReader {

    private data class Key(val path: String, val length: Long, val modified: Long)

    /** Absent from the map means not read yet; a null value is cached as [NONE]. */
    private val cache = ConcurrentHashMap<Key, Any>()

    override fun readDateTaken(file: File): Pair<LocalDateTime, ZoneOffset?>? {
        val key = Key(file.path, file.length(), file.lastModified())
        val cached = cache.getOrPut(key) { delegate.readDateTaken(file) ?: NONE }
        @Suppress("UNCHECKED_CAST")
        return if (cached === NONE) null else cached as Pair<LocalDateTime, ZoneOffset?>
    }

    private companion object {
        val NONE = Any()
    }
}
