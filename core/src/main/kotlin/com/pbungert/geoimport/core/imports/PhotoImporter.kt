package com.pbungert.geoimport.core.imports

import java.io.File
import java.time.LocalDateTime
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
    private val captureTime: CaptureTimeResolver,
    private val log: (String) -> Unit,
    private val extensions: Set<String> = DEFAULT_EXTENSIONS,
) {
    data class ImportResult(val copied: List<File>, val destFolder: File?)

    /** Files this importer will pick up — also what a resume point may name. */
    fun isImportable(file: File) = file.extension.lowercase() in extensions

    fun run(
        startFilename: String?,
        startTimestamp: LocalDateTime?,
        onCopyProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult {
        val sourceFiles = collectSourceFiles(sourcePath) ?: return ImportResult(emptyList(), null)

        log("Found ${describe(sourceFiles)}.")

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
        basePath.listFiles { f -> f.isDirectory && DCF_FOLDER_PATTERN.matches(f.name) }
            ?.sortedBy { it.name }
            ?.forEach { dir ->
                dir.listFiles { f -> f.isFile && isImportable(f) }?.let { files.addAll(it) }
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
            // Only importable files may become the watermark. Geotagging
            // leaves .xmp sidecars in this folder, and a sidecar shares its
            // sequence number with the photo it belongs to — so picking one
            // would yield a name that matches nothing on the card, leaving
            // `started` false in filterNewFiles and silently importing zero
            // files on every subsequent run.
            val lastFile = sortByFilenameChronological(
                lastFolder.listFiles()?.filter { it.isFile && isImportable(it) } ?: emptyList()
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
                    val dateTaken = captureTime.localOf(file)
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

    private fun importFolderName(number: Int) = "Import %02d".format(number)

    companion object {
        /**
         * DCF directory names: three digits plus five free characters, so
         * `101_FUJI` as well as `100CANON`, `100MSDCF`, `100OLYMP`.
         */
        val DCF_FOLDER_PATTERN = Regex("""^\d{3}[0-9A-Za-z_]{5}$""")

        /**
         * Formats collected by default. Anything the built-in writer cannot
         * embed into still imports and gets an XMP sidecar.
         */
        val DEFAULT_EXTENSIONS = setOf("raf", "jpg", "jpeg", "mov", "mp4")

        /** Counts per extension, e.g. "12 RAF, 3 MOV" — no format is hardcoded. */
        fun describe(files: List<File>): String {
            if (files.isEmpty()) return "no files"
            return files.groupingBy { it.extension.uppercase() }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .joinToString(", ") { "${it.value} ${it.key}" }
        }

        /** The last run of digits in the filename. */
        val FILENAME_SEQUENCE_PATTERN = Regex("""(\d+)(?!.*\d)""")

        private data class FilenameSequence(val value: Int, val width: Int)

        private fun parseFilenameSequence(filename: String): FilenameSequence? {
            val match = FILENAME_SEQUENCE_PATTERN.find(filename.substringBeforeLast('.'))
                ?: return null
            val value = match.value.toIntOrNull() ?: return null
            return FilenameSequence(value, match.value.length)
        }

        /**
         * Sorts by the trailing number in the filename, then rotates so the
         * sequence starts after the largest gap — this keeps chronological order
         * when the camera's file counter has wrapped around.
         */
        fun sortByFilenameChronological(files: List<File>): List<File> {
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
    }
}
