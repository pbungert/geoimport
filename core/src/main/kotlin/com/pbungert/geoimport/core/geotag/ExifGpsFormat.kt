package com.pbungert.geoimport.core.geotag

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * EXIF GPS tag value formatting, shared so that every platform writes the same
 * values even though the mechanism differs (androidx.exifinterface takes these
 * strings, Commons Imaging takes doubles but must agree on rounding).
 */
object ExifGpsFormat {

    fun latitudeRef(lat: Double) = if (lat >= 0) "N" else "S"

    fun longitudeRef(lon: Double) = if (lon >= 0) "E" else "W"

    /** EXIF rational triplet "deg/1,min/1,sec*1e4/1e4" (~3 mm precision). */
    fun toDmsRational(value: Double): String {
        var secondsE4 = (abs(value) * 3600.0 * 10000.0).roundToLong()
        val degrees = secondsE4 / 36_000_000
        secondsE4 %= 36_000_000
        val minutes = secondsE4 / 600_000
        secondsE4 %= 600_000
        return "$degrees/1,$minutes/1,$secondsE4/10000"
    }

    /** Metres above/below the reference ellipsoid as a millimetre rational. */
    fun altitudeRational(ele: Double) = "${(abs(ele) * 1000).roundToLong()}/1000"

    /** 0 = above sea level, 1 = below — the sign lives here, not in the value. */
    fun altitudeRef(ele: Double) = if (ele < 0) "1" else "0"

    /** GPSTimeStamp is always UTC, per the EXIF spec. */
    fun timeStamp(time: Instant): String = time.atOffset(ZoneOffset.UTC).format(GPS_TIME)

    /** GPSDateStamp is always UTC, per the EXIF spec. */
    fun dateStamp(time: Instant): String = time.atOffset(ZoneOffset.UTC).format(GPS_DATE)

    private val GPS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val GPS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd")
}
