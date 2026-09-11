package com.pbungert.geoimport.core.geotag

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * EXIF GPS tag values, shared so every platform writes identical numbers.
 *
 * The write mechanisms differ - androidx.exifinterface takes preformatted
 * rational strings, Commons Imaging takes numeric types - so the rounding is
 * done here once and both backends build their own representation from the
 * same parts. Without that, the phone and the desktop could disagree in the
 * last decimal on the same photo.
 */
object ExifGpsFormat {

    /** Degrees, arcminutes, and arcseconds scaled by 10^4 (~3 mm). */
    data class Dms(val degrees: Long, val minutes: Long, val secondsE4: Long)

    fun latitudeRef(lat: Double) = if (lat >= 0) "N" else "S"

    fun longitudeRef(lon: Double) = if (lon >= 0) "E" else "W"

    fun toDms(value: Double): Dms {
        var secondsE4 = (abs(value) * 3600.0 * 10000.0).roundToLong()
        val degrees = secondsE4 / 36_000_000
        secondsE4 %= 36_000_000
        val minutes = secondsE4 / 600_000
        secondsE4 %= 600_000
        return Dms(degrees, minutes, secondsE4)
    }

    /** EXIF rational triplet "deg/1,min/1,sec*1e4/1e4". */
    fun toDmsRational(value: Double): String = with(toDms(value)) {
        "$degrees/1,$minutes/1,$secondsE4/10000"
    }

    /** Altitude magnitude in millimetres; the sign lives in [altitudeRef]. */
    fun altitudeMillimetres(ele: Double) = (abs(ele) * 1000).roundToLong()

    /** Metres above/below the reference ellipsoid as a millimetre rational. */
    fun altitudeRational(ele: Double) = "${altitudeMillimetres(ele)}/1000"

    /** 0 = above sea level, 1 = below — the sign lives here, not in the value. */
    fun altitudeRef(ele: Double) = if (ele < 0) "1" else "0"

    /** Hours, minutes, seconds in UTC - GPSTimeStamp is always UTC per spec. */
    fun timeStampParts(time: Instant): Triple<Int, Int, Int> =
        time.atOffset(ZoneOffset.UTC).let { Triple(it.hour, it.minute, it.second) }

    /** GPSTimeStamp is always UTC, per the EXIF spec. */
    fun timeStamp(time: Instant): String = time.atOffset(ZoneOffset.UTC).format(GPS_TIME)

    /** GPSDateStamp is always UTC, per the EXIF spec. */
    fun dateStamp(time: Instant): String = time.atOffset(ZoneOffset.UTC).format(GPS_DATE)

    private val GPS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val GPS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd")
}
