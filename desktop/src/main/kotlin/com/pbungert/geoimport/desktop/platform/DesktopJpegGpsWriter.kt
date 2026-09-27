package com.pbungert.geoimport.desktop.platform

import com.pbungert.geoimport.core.geotag.ExifGpsFormat
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.spi.JpegGpsWriter
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.common.RationalNumber
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [JpegGpsWriter] backed by Apache Commons Imaging — the desktop counterpart
 * to androidx.exifinterface.
 *
 * The rewrite is lossless: the image data is copied untouched and only the
 * EXIF segment is rebuilt. Values come from
 * [com.pbungert.geoimport.core.geotag.ExifGpsFormat] so this agrees with the
 * Android backend to the last digit.
 */
object DesktopJpegGpsWriter : JpegGpsWriter {

    override fun writeGps(jpeg: File, point: TrackPoint) {
        val existing = (Imaging.getMetadata(jpeg) as? JpegImageMetadata)?.exif?.outputSet
        val outputSet = existing ?: TiffOutputSet()

        val gps = outputSet.orCreateGpsDirectory
        // Replace rather than append: a second copy of a GPS tag is invalid.
        listOf(
            GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF,
            GpsTagConstants.GPS_TAG_GPS_LATITUDE,
            GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF,
            GpsTagConstants.GPS_TAG_GPS_LONGITUDE,
            GpsTagConstants.GPS_TAG_GPS_ALTITUDE,
            GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF,
            GpsTagConstants.GPS_TAG_GPS_TIME_STAMP,
            GpsTagConstants.GPS_TAG_GPS_DATE_STAMP,
        ).forEach { gps.removeField(it) }

        // Built from ExifGpsFormat's parts rather than setGpsInDegrees, which
        // rounds its own way: the rationals here are the ones Android writes.
        gps.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE_REF, ExifGpsFormat.latitudeRef(point.lat))
        gps.add(GpsTagConstants.GPS_TAG_GPS_LATITUDE, *dmsRationals(point.lat))
        gps.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE_REF, ExifGpsFormat.longitudeRef(point.lon))
        gps.add(GpsTagConstants.GPS_TAG_GPS_LONGITUDE, *dmsRationals(point.lon))

        point.ele?.let { ele ->
            gps.add(
                GpsTagConstants.GPS_TAG_GPS_ALTITUDE,
                RationalNumber(ExifGpsFormat.altitudeMillimetres(ele).toInt(), 1000),
            )
            gps.add(
                GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF,
                ExifGpsFormat.altitudeRef(ele).toByte(),
            )
        }

        val (hours, minutes, seconds) = ExifGpsFormat.timeStampParts(point.time)
        gps.add(
            GpsTagConstants.GPS_TAG_GPS_TIME_STAMP,
            RationalNumber(hours, 1),
            RationalNumber(minutes, 1),
            RationalNumber(seconds, 1),
        )
        gps.add(GpsTagConstants.GPS_TAG_GPS_DATE_STAMP, ExifGpsFormat.dateStamp(point.time))

        writeLosslessly(jpeg, outputSet)
    }

    /** The same "deg/1,min/1,sec*1e4/1e4" triplet as [ExifGpsFormat.toDmsRational]. */
    private fun dmsRationals(value: Double): Array<RationalNumber> = with(ExifGpsFormat.toDms(value)) {
        arrayOf(
            RationalNumber(degrees.toInt(), 1),
            RationalNumber(minutes.toInt(), 1),
            RationalNumber(secondsE4.toInt(), 10_000),
        )
    }

    private fun writeLosslessly(jpeg: File, outputSet: TiffOutputSet) {
        val temp = File(jpeg.parentFile, jpeg.name + ".exif.tmp")
        try {
            temp.outputStream().buffered().use { out ->
                ExifRewriter().updateExifMetadataLossless(jpeg, out, outputSet)
            }
            Files.move(temp.toPath(), jpeg.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}
