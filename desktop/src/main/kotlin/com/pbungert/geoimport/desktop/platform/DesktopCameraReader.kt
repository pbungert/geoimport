package com.pbungert.geoimport.desktop.platform

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.pbungert.geoimport.core.imports.Camera
import com.pbungert.geoimport.core.spi.CameraReader
import java.io.File

/** [CameraReader] backed by metadata-extractor. */
object DesktopCameraReader : CameraReader {

    override fun readCamera(file: File): Camera? = try {
        val metadata = ImageMetadataReader.readMetadata(file)
        val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        Camera.of(
            make = ifd0?.getString(ExifIFD0Directory.TAG_MAKE),
            model = ifd0?.getString(ExifIFD0Directory.TAG_MODEL),
            serial = sub?.getString(ExifSubIFDDirectory.TAG_BODY_SERIAL_NUMBER),
        )
    } catch (_: Exception) {
        null
    }
}
