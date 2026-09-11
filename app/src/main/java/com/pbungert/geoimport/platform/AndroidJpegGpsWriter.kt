package com.pbungert.geoimport.platform

import androidx.exifinterface.media.ExifInterface
import com.pbungert.geoimport.core.geotag.ExifGpsFormat
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.spi.JpegGpsWriter
import java.io.File

/** [JpegGpsWriter] backed by androidx.exifinterface. */
object AndroidJpegGpsWriter : JpegGpsWriter {

    override fun writeGps(jpeg: File, point: TrackPoint) {
        val exif = ExifInterface(jpeg)
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, ExifGpsFormat.latitudeRef(point.lat))
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, ExifGpsFormat.toDmsRational(point.lat))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, ExifGpsFormat.longitudeRef(point.lon))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, ExifGpsFormat.toDmsRational(point.lon))
        point.ele?.let { ele ->
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, ExifGpsFormat.altitudeRational(ele))
            exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, ExifGpsFormat.altitudeRef(ele))
        }
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, ExifGpsFormat.timeStamp(point.time))
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, ExifGpsFormat.dateStamp(point.time))
        exif.saveAttributes()
    }
}
