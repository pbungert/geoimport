package com.pbungert.geoimport

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pbungert.geoimport.core.geotag.Geotagger
import com.pbungert.geoimport.core.geotag.RafGpsWriter
import com.pbungert.geoimport.core.imports.PhotoImporter
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.track.TrackParser
import com.pbungert.geoimport.platform.AndroidExifDateReader
import com.pbungert.geoimport.platform.AndroidJpegGpsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Coarse UI state driving the progress bar / summary, separate from the debug log. */
sealed interface Phase {
    data object Idle : Phase

    /** [total] == 0 means the work is indeterminate (no count yet). */
    data class Busy(val label: String, val current: Int, val total: Int) : Phase
    data class Done(val summary: ImportSummary) : Phase
    data class Failed(val message: String) : Phase
}

data class ImportSummary(
    val destFolder: String?,
    val copied: Int,
    val rafCount: Int,
    val movCount: Int,
    /** Whether a track was supplied and geotagging was attempted. */
    val geotagAttempted: Boolean,
    val exifTagged: Int,
    val sidecarTagged: Int,
    val notTagged: Int,
)

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val rafGpsWriter = RafGpsWriter(AndroidJpegGpsWriter)

    // Options mirroring the script's arguments
    var startFilename by mutableStateOf("")
    var startTimestamp by mutableStateOf("")
    var toleranceMinutes by mutableStateOf("30")

    var trackUri by mutableStateOf<Uri?>(null)
        private set
    var trackName by mutableStateOf<String?>(null)
        private set

    /** Points of the selected track for the map preview; null while unparsed. */
    var trackPoints by mutableStateOf<List<TrackPoint>?>(null)
        private set

    var running by mutableStateOf(false)
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    val logLines = mutableStateListOf<String>()

    fun selectTrack(uri: Uri) {
        trackUri = uri
        trackName = queryDisplayName(uri) ?: uri.lastPathSegment
        trackPoints = null
        viewModelScope.launch(Dispatchers.IO) {
            val points = try {
                getApplication<Application>().contentResolver
                    .openInputStream(uri)!!.use { TrackParser.parse(it) }
            } catch (_: Exception) {
                emptyList()
            }
            launch(Dispatchers.Main.immediate) {
                if (trackUri == uri) trackPoints = points
            }
        }
    }

    fun clearTrack() {
        trackUri = null
        trackName = null
        trackPoints = null
    }

    fun startImport() {
        if (running) return
        running = true
        logLines.clear()
        postPhase(Phase.Busy("Preparing…", 0, 0))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runImport()
            } catch (e: Exception) {
                fail("Import failed: ${e.message ?: e}")
            } finally {
                running = false
            }
        }
    }

    private fun runImport() {
        val app = getApplication<Application>()
        log("Starting Photo Importer...")

        val startFn = startFilename.trim().ifEmpty { null }
        val startTsText = startTimestamp.trim().ifEmpty { null }
        val startTs = startTsText?.let {
            parseTimestampInput(it) ?: run {
                fail("Could not parse timestamp '$it'. Use e.g. 2026-07-17 14:30.")
                return
            }
        }
        val tolerance = Duration.ofMinutes(toleranceMinutes.trim().toLongOrNull() ?: 30L)

        // Load the track up front so a broken file aborts before anything is copied.
        val track = trackUri?.let { uri ->
            try {
                app.contentResolver.openInputStream(uri)!!.use { TrackParser.parse(it) }
            } catch (e: Exception) {
                fail("Failed to read track file: ${e.message ?: e}")
                return
            }
        }
        when {
            track == null ->
                log("No track file selected — importing without geotagging.")
            track.isEmpty() -> {
                fail("Track file contains no timestamped points — cannot geotag.")
                return
            }
            else ->
                log("Loaded ${track.size} track points from ${trackName ?: "track file"}.")
        }

        val storageManager = app.getSystemService(StorageManager::class.java)
        val volume = storageManager.storageVolumes
            .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        if (volume == null) {
            fail("No SD card found. Insert a card and try again.")
            return
        }
        val volumeDir = resolveVolumeDirectory(volume)
        if (volumeDir == null) {
            fail(
                "SD card '${volume.getDescription(app)}' is mounted but not readable by the app " +
                    "(tried ${listOfNotNull(volume.directory?.path, volume.uuid?.let { "/storage/$it" }).joinToString(", ")})."
            )
            return
        }
        log("SD card found: ${volume.getDescription(app)} (${volumeDir.path})")

        val source = File(volumeDir, "DCIM")
        val destBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

        val importer = PhotoImporter(source, destBase, AndroidExifDateReader, ::log)
        postPhase(Phase.Busy("Importing photos", 0, 0))
        val result = importer.run(startFn, startTs) { done, total ->
            postPhase(Phase.Busy("Importing photos", done, total))
        }
        if (result.copied.isEmpty()) {
            log("Nothing to import.")
            postPhase(Phase.Done(ImportSummary(null, 0, 0, 0, track != null, 0, 0, 0)))
            return
        }

        var exifTagged = 0
        var sidecarTagged = 0
        var notTagged = 0
        if (track != null) {
            val geotagger = Geotagger(track, tolerance)
            val total = result.copied.size
            postPhase(Phase.Busy("Geotagging", 0, total))
            for ((index, file) in result.copied.withIndex()) {
                val point = geotagger.locate(importer.getDateTakenInstant(file))
                if (point == null) {
                    log("No track point within tolerance for ${file.name} — not geotagged.")
                    notTagged++
                    postPhase(Phase.Busy("Geotagging", index + 1, total))
                    continue
                }
                if (file.extension.equals("raf", ignoreCase = true)) {
                    // Lightroom for Android ignores XMP sidecars, so the GPS
                    // position has to live in the RAF's EXIF block itself.
                    try {
                        rafGpsWriter.writeGps(file, point)
                        exifTagged++
                    } catch (e: Exception) {
                        log(
                            "EXIF write failed for ${file.name} (${e.message ?: e}) — " +
                                "writing XMP sidecar instead."
                        )
                        geotagger.writeSidecar(file, point)
                        sidecarTagged++
                    }
                } else {
                    // Videos carry no EXIF; leave a sidecar for desktop tools.
                    geotagger.writeSidecar(file, point)
                    sidecarTagged++
                }
                postPhase(Phase.Busy("Geotagging", index + 1, total))
            }
            log(
                "Geotagged ${exifTagged + sidecarTagged} of ${result.copied.size} files " +
                    "($exifTagged EXIF, $sidecarTagged XMP sidecars)."
            )
        }

        MediaScannerConnection.scanFile(
            app, result.copied.map { it.path }.toTypedArray(), null, null
        )
        log("Import complete! ${result.copied.size} files in '${result.destFolder?.name}'.")
        postPhase(
            Phase.Done(
                ImportSummary(
                    destFolder = result.destFolder?.name,
                    copied = result.copied.size,
                    rafCount = result.copied.count { it.extension.equals("raf", ignoreCase = true) },
                    movCount = result.copied.count { it.extension.equals("mov", ignoreCase = true) },
                    geotagAttempted = track != null,
                    exifTagged = exifTagged,
                    sidecarTagged = sidecarTagged,
                    notTagged = notTagged,
                )
            )
        )
    }

    /**
     * Returns the app-accessible mount point of the volume. [StorageVolume.getDirectory]
     * sometimes reports the raw mount (/mnt/media_rw/XXXX-XXXX), which only system
     * processes may read — apps must use the FUSE view at /storage/XXXX-XXXX instead.
     */
    private fun resolveVolumeDirectory(volume: StorageVolume): File? {
        val candidates = buildList {
            volume.uuid?.let { add(File("/storage/$it")) }
            volume.directory?.let { add(it) }
            // Last resort: any mounted /storage entry besides the internal ones.
            File("/storage").listFiles()?.forEach {
                if (it.name != "emulated" && it.name != "self") add(it)
            }
        }.distinctBy { it.path }

        return candidates.firstOrNull { File(it, "DCIM").isDirectory }
            ?: candidates.firstOrNull { it.isDirectory && it.listFiles() != null }
    }

    private fun log(message: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) { logLines.add(message) }
    }

    private fun postPhase(next: Phase) {
        viewModelScope.launch(Dispatchers.Main.immediate) { phase = next }
    }

    /** Logs [message] and moves the UI into the failed state. */
    private fun fail(message: String) {
        log(message)
        postPhase(Phase.Failed(message))
    }

    private fun parseTimestampInput(text: String): LocalDateTime? {
        for (pattern in TIMESTAMP_PATTERNS) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
            } catch (_: DateTimeParseException) {
            }
        }
        try {
            return LocalDate.parse(text).atStartOfDay()
        } catch (_: DateTimeParseException) {
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private companion object {
        val TIMESTAMP_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
        )
    }
}
