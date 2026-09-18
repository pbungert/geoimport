package com.pbungert.geoimport.core.imports

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The timestamp forms a resume point may be typed in.
 *
 * Local, with no zone: the time a user types is read off the camera or off the
 * files, not off a corrected clock, which is what [CaptureTimeResolver.localOf]
 * compares it against.
 */
private val FORMATS = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm",
).map { DateTimeFormatter.ofPattern(it) }

/**
 * Null when [text] is not a timestamp at all. The CLI then treats what it was
 * given as a filename instead; the app reports it as unparseable, because
 * there is a separate field for a filename.
 */
fun parseResumeTimestamp(text: String): LocalDateTime? {
    val trimmed = text.trim()
    for (format in FORMATS) {
        try {
            return LocalDateTime.parse(trimmed, format)
        } catch (_: DateTimeParseException) {
        }
    }
    // A bare date means the start of that day.
    return try {
        LocalDate.parse(trimmed).atStartOfDay()
    } catch (_: DateTimeParseException) {
        null
    }
}
