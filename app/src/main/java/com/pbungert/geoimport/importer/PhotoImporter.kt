package com.pbungert.geoimport.importer

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.pow

/**
 * Port of the photo-importer C# script: collects RAF/MOV files from the
 * NNN_FUJI folders on the card, skips everything up to the last imported file
 * (or an explicit start filename/timestamp) and copies the rest into the next
 * "Import NN" folder below [destBasePath].
 */
class PhotoImporter(
    private val sourcePath: File,
    private val destBasePath: File,
    private val log: (String) -> Unit,
) {
    data class ImportResult(val copied: List<File>, val destFolder: File?)

    fun run(
        startFilename: String?,
        startTimestamp: LocalDateTime?,
        onCopyProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult {
        val sourceFiles = collectSourceFiles(sourcePath) ?: return ImportResult(emptyList(), null)

        log(
            "Found ${sourceFiles.count { it.extension.equals("raf", ignoreCase = true) }} RAF files " +
                "and ${sourceFiles.count { it.extension.equals("mov", ignoreCase = true) }} MOV files."
        )

        val (resolvedFilename, resolvedTimestamp) = resolveStartCriteria(startFilename, startTimestamp)
        val filesToCopy = filterNewFiles(sourceFiles, resolvedFilename, resolvedTimestamp)
        log("After filtering, ${filesToCopy.size} files will be copied.")
        if (filesToCopy.isEmpty()) return ImportResult(emptyList(), null)

        val destFolder = createNextImportFolder()
        val copied = mutableListOf<File>()
        val total = filesToCopy.size
        onCopyProgress(0, total)
        for ((index, file) in filesToCopy.withIndex()) {
            log("Copying ${file.name}...")
            val dest = file.copyTo(File(destFolder, file.name), overwrite = false)
            dest.setLastModified(file.lastModified())
            copied.add(dest)
            onCopyProgress(index + 1, total)
        }
        return ImportResult(copied, destFolder)
    }

    private fun collectSourceFiles(basePath: File): List<File>? {
        if (!basePath.isDirectory) {
            log("Source folder '${basePath.path}' not found.")
            return null
        }

        val files = mutableListOf<File>()
        basePath.listFiles { f -> f.isDirectory && FUJI_FOLDER_PATTERN.matches(f.name) }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                dir.listFiles { f ->
                    f.isFile &&
                        (f.extension.equals("raf", ignoreCase = true) ||
                            f.extension.equals("mov", ignoreCase = true))
                }?.let { files.addAll(it) }
            }
        return files
    }

    private fun resolveStartCriteria(
        startFilename: String?,
        startTimestamp: LocalDateTime?,
    ): Pair<String?, LocalDateTime?> {
        if (startTimestamp != null) {
            log("Starting from timestamp: $startTimestamp")
            return null to startTimestamp
        }
        if (startFilename != null) {
            log("Starting from filename: $startFilename")
            return startFilename to null
        }

        val highestNumber = findHighestImportNumber()
        if (highestNumber > 0) {
            val lastFolder = File(destBasePath, importFolderName(highestNumber))
            val lastFile = sortByFilenameChronological(
                lastFolder.listFiles()?.filter { it.isFile } ?: emptyList()
            ).lastOrNull()
            if (lastFile != null) {
                log("Starting after '${lastFile.name}' from '${lastFolder.path}'")
                return lastFile.name to null
            }
        }

        return null to null
    }

    private fun filterNewFiles(
        files: List<File>,
        startFilename: String?,
        startTimestamp: LocalDateTime?,
    ): List<File> {
        val sorted = sortByFilenameChronological(files)

        if (startFilename == null && startTimestamp == null) return sorted

        var started = false
        val result = mutableListOf<File>()

        for (file in sorted) {
            if (!started) {
                if (startFilename != null && file.name.equals(startFilename, ignoreCase = true)) {
                    started = true
                    continue // skip the matched file itself
                } else if (startTimestamp != null) {
                    val dateTaken = getDateTaken(file)
                    if (dateTaken.isEqual(startTimestamp)) {
                        started = true
                        continue // skip the matched file itself
                    } else if (dateTaken.isAfter(startTimestamp)) {
                        started = true // file is strictly newer — include it
                    }
                }
            }

            if (started) result.add(file)
        }

        return result
    }

    /**
     * Sorts by the trailing number in the filename, then rotates so the
     * sequence starts after the largest gap — this keeps chronological order
     * when the camera's file counter has wrapped around.
     */
    private fun sortByFilenameChronological(files: List<File>): List<File> {
        val parsed = files
            .mapNotNull { f -> parseFilenameSequence(f.name)?.let { f to it } }
            .sortedBy { it.second.value }

        if (parsed.size < 2) return parsed.map { it.first }

        val modulus = 10.0.pow(parsed.maxOf { it.second.width }).toInt()

        var bestGap = -1
        var bestIndex = 0
        for (i in 0 until parsed.size - 1) {
            val gap = parsed[i + 1].second.value - parsed[i].second.value
            if (gap > bestGap) {
                bestGap = gap
                bestIndex = i + 1
            }
        }

        val wrapGap = parsed[0].second.value + modulus - parsed.last().second.value
        val startIndex = if (wrapGap > bestGap) 0 else bestIndex

        return (parsed.drop(startIndex) + parsed.take(startIndex)).map { it.first }
    }

    private fun createNextImportFolder(): File {
        destBasePath.mkdirs()
        val folder = File(destBasePath, importFolderName(findHighestImportNumber() + 1))
        log("Creating destination folder: ${folder.path}...")
        folder.mkdirs()
        return folder
    }

    private fun findHighestImportNumber(): Int {
        if (!destBasePath.isDirectory) return 0

        return destBasePath.listFiles { f -> f.isDirectory && f.name.startsWith("Import ") }
            ?.map { it.name.replace("Import ", "").toIntOrNull() ?: 0 }
            ?.maxOrNull() ?: 0
    }

    fun getDateTaken(file: File): LocalDateTime {
        readExifDateTaken(file)?.let { (local, _) -> return local }
        return LocalDateTime.ofInstant(fileFallbackTime(file), ZoneId.systemDefault())
    }

    /** Date taken as an absolute instant, using the EXIF time offset when present. */
    fun getDateTakenInstant(file: File): Instant {
        readExifDateTaken(file)?.let { (local, offset) ->
            return if (offset != null) local.toInstant(offset)
            else local.atZone(ZoneId.systemDefault()).toInstant()
        }
        return fileFallbackTime(file)
    }

    private fun readExifDateTaken(file: File): Pair<LocalDateTime, ZoneOffset?>? {
        try {
            val exif = ExifInterface(file)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            if (!dateStr.isNullOrEmpty()) {
                val local = LocalDateTime.parse(dateStr, EXIF_DATE_FORMAT)
                val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let {
                    try {
                        ZoneOffset.of(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                return local to offset
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun fileFallbackTime(file: File): Instant {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return Instant.ofEpochMilli(
            min(attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis())
        )
    }

    private data class FilenameSequence(val value: Int, val width: Int)

    private fun parseFilenameSequence(filename: String): FilenameSequence? {
        val match = FILENAME_SEQUENCE_PATTERN.find(filename.substringBeforeLast('.')) ?: return null
        val value = match.value.toIntOrNull() ?: return null
        return FilenameSequence(value, match.value.length)
    }

    private fun importFolderName(number: Int) = "Import %02d".format(number)

    private companion object {
        val FUJI_FOLDER_PATTERN = Regex("""^\d{3}_FUJI$""")

        /** The last run of digits in the filename. */
        val FILENAME_SEQUENCE_PATTERN = Regex("""(\d+)(?!.*\d)""")

        val EXIF_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    }
}
