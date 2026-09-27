package com.pbungert.geoimport.core.imports

import com.pbungert.geoimport.core.geotag.Geotagger
import com.pbungert.geoimport.core.geotag.GpsWriter
import java.io.File
import java.io.IOException
import java.time.Instant
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
    /**
     * [copies] pairs each copy with the entry it was made from. That pairing is
     * what a caller must tag by, not the file name: two card folders can hold
     * the same name, only one of them can land in the flat destination, and
     * looking the other up by name finds the wrong photo.
     */
    data class ImportResult(val copies: List<Pair<PlannedFile, File>>, val destFolder: File?) {
        val copied get() = copies.map { it.second }
    }

    /** Files this importer will pick up — also what a resume point may name. */
    fun isImportable(file: File) = file.extension.lowercase() in extensions

    fun run(
        startFilename: String?,
        startTimestamp: LocalDateTime?,
        onCopyProgress: (done: Int, total: Int, justCopied: String?) -> Unit = { _, _, _ -> },
    ): ImportResult = execute(plan(startFilename, startTimestamp), onCopyProgress)

    /**
     * Works out what would be copied, and where, without creating anything.
     *
     * Pass [geotaggerFor] and [writer] to resolve each file's position and the
     * writer that would handle it, so a preview can show the outcome before
     * committing to it.
     *
     * [geotaggerFor] is handed the capture times of the files that survived the
     * resume filter, and only then decides what to match them against — which
     * is what lets a caller pick the tracks that actually cover this import out
     * of a folder full of them. Nothing before that point depends on the track.
     */
    fun plan(
        startFilename: String?,
        startTimestamp: LocalDateTime?,
        writer: GpsWriter? = null,
        geotaggerFor: (captureTimes: List<Instant>) -> Geotagger? = { null },
    ): ImportPlan {
        val destFolder = File(destBasePath, importFolderName(findHighestImportNumber() + 1))
        val sourceFiles = collectSourceFiles(sourcePath)
            ?: return ImportPlan(sourcePath, destFolder, null, emptyList())

        log("Found ${describe(sourceFiles)}.")

        val (resolvedFilename, resolvedTimestamp) = resolveStartCriteria(startFilename, startTimestamp)
        val filesToCopy = filterNewFiles(sourceFiles, resolvedFilename, resolvedTimestamp)
        log("After filtering, ${filesToCopy.size} files will be copied.")

        // Resolved once and carried through: reading EXIF off a card is the
        // slow part, and both the track filter and the entries need it.
        val timed = filesToCopy.map { it to captureTime.instantOf(it) }
        val geotagger = geotaggerFor(timed.map { it.second })

        val entries = timed.map { (file, time) ->
            val resolved = geotagger?.resolve(time)
            val fix = resolved?.point
            PlannedFile(
                source = file,
                destination = File(destFolder, file.name),
                captureTime = time,
                fix = fix,
                gapMeters = resolved?.gapMeters,
                writer = if (fix == null) null else writer?.effectiveWriterFor(file)?.name,
            )
        }
        return ImportPlan(sourcePath, destFolder, resolvedFilename, entries)
    }

    /**
     * Copies the selected files. A file that cannot be copied is reported and
     * skipped rather than aborting the run: the old behaviour left a
     * half-populated folder, which then became the resume watermark.
     */
    fun execute(
        plan: ImportPlan,
        onCopyProgress: (done: Int, total: Int, justCopied: String?) -> Unit = { _, _, _ -> },
    ): ImportResult {
        val selected = plan.selected
        if (selected.isEmpty()) return ImportResult(emptyList(), null)

        log("Creating destination folder: ${plan.destFolder.path}...")
        plan.destFolder.mkdirs()

        val copies = mutableListOf<Pair<PlannedFile, File>>()
        val failed = mutableListOf<String>()
        val total = selected.size
        onCopyProgress(0, total, null)
        for ((index, entry) in selected.withIndex()) {
            log("Copying ${entry.source.name}...")
            try {
                val dest = entry.source.copyTo(entry.destination, overwrite = false)
                dest.setLastModified(entry.source.lastModified())
                copies.add(entry to dest)
            } catch (e: Exception) {
                log("Could not copy ${entry.source.name}: ${e.message ?: e}")
                failed.add(entry.source.name)
            }
            onCopyProgress(index + 1, total, entry.source.name)
        }
        if (failed.isNotEmpty()) {
            log("${failed.size} of $total files could not be copied: ${failed.joinToString(", ")}")
        }
        if (copies.isEmpty()) throw IOException("no files could be copied")
        return ImportResult(copies, plan.destFolder)
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
        // No number in the name means no place in the camera's sequence, so
        // these are judged on their own below rather than dropped unseen.
        val unnumbered = (files - sorted.toSet()).sortedBy { it.name }

        if (startFilename == null && startTimestamp == null) return sorted + unnumbered
        if (startTimestamp != null) {
            val newer = unnumbered.filter { captureTime.localOf(it).isAfter(startTimestamp) }
            return filterNumbered(sorted, null, startTimestamp) + newer
        }
        if (unnumbered.isNotEmpty()) {
            // Nothing says whether they came before the resume file or after
            // it, and importing them every run would duplicate them.
            log(
                "Skipped ${unnumbered.size} files with no number in their name, which a " +
                    "filename resume cannot place: ${unnumbered.joinToString(", ") { it.name }}. " +
                    "Resume from a timestamp to include them."
            )
        }
        return filterNumbered(sorted, startFilename, null)
    }

    private fun filterNumbered(
        sorted: List<File>,
        startFilename: String?,
        startTimestamp: LocalDateTime?,
    ): List<File> {

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
                // A RAW and its JPEG share a number. Without the name as a tie
                // break their order is whatever the directory listing gave, so
                // the resume file and the card could disagree on which of a
                // pair came last - and the other would be imported again.
                .sortedWith(
                    compareBy<Pair<File, FilenameSequence>> { it.second.value }
                        .thenBy { it.first.name.lowercase() }
                )

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
