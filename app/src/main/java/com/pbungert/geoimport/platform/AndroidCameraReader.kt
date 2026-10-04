package com.pbungert.geoimport.platform

import androidx.exifinterface.media.ExifInterface
import com.pbungert.geoimport.core.imports.Camera
import com.pbungert.geoimport.core.spi.CameraReader
import java.io.File

/** [CameraReader] backed by androidx.exifinterface. */
object AndroidCameraReader : CameraReader {

    override fun readCamera(file: File): Camera? = try {
        val exif = ExifInterface(file)
        Camera.of(
            make = exif.getAttribute(ExifInterface.TAG_MAKE),
            model = exif.getAttribute(ExifInterface.TAG_MODEL),
            serial = exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER),
        )
    } catch (_: Exception) {
        null
    }
}
