package com.pbungert.geoimport.desktop.cli

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Timestamp forms accepted for `--resume`, mirroring the Android app's. */
private val TIMESTAMP_FORMATS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd HH:mm",
).map { DateTimeFormatter.ofPattern(it) }

/** Null when [text] is not a timestamp — the caller then treats it as a filename. */
fun parseTimestamp(text: String): LocalDateTime? {
    val t = text.trim()
    for (format in TIMESTAMP_FORMATS) {
        try {
            return LocalDateTime.parse(t, format)
        } catch (_: DateTimeParseException) {
        }
    }
    return try {
        LocalDate.parse(t).atStartOfDay()
    } catch (_: DateTimeParseException) {
        null
    }
}
