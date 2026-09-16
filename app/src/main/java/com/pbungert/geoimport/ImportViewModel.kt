package com.pbungert.geoimport

import android.app.Application
import android.content.Context
import android.content.Intent
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
import com.pbungert.geoimport.core.geotag.BuiltInGpsWriter
import com.pbungert.geoimport.core.geotag.FallbackGpsWriter
import com.pbungert.geoimport.core.geotag.Geotagger
import com.pbungert.geoimport.core.geotag.GpsWriteResult
import com.pbungert.geoimport.core.geotag.XmpSidecarWriter
import com.pbungert.geoimport.core.imports.CaptureTimeResolver
import com.pbungert.geoimport.core.imports.ImportPlan
import com.pbungert.geoimport.core.imports.PlannedFile
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.reflect.KProperty
import kotlin.math.roundToLong

/** Coarse UI state driving the progress bar / summary, separate from the debug log. */
sealed interface Phase {
    data object Idle : Phase

    /** Preview: what the import would do, awaiting confirmation. */
    data class Planned(val plan: ImportPlan) : Phase

    /** [total] == 0 means the work is indeterminate (no count yet). */
    data class Busy(val label: String, val current: Int, val total: Int) : Phase
    data class Done(val summary: ImportSummary) : Phase
    data class Failed(val message: String) : Phase
}

data class ImportSummary(
    val destFolder: String?,
    val copied: Int,
    /** Counts per extension, e.g. "12 RAF, 3 MOV" - no format is hardcoded. */
    val breakdown: String,
    /** Whether a track was supplied and geotagging was attempted. */
    val geotagAttempted: Boolean,
    val exifTagged: Int,
    val sidecarTagged: Int,
    val notTagged: Int,
)

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    /** Embed where possible, sidecar otherwise; never lose a position. */
    private val gpsWriter = FallbackGpsWriter(
        listOf(BuiltInGpsWriter(AndroidJpegGpsWriter), XmpSidecarWriter),
        onFallback = { file, writer, e ->
            log("${writer.name} write failed for ${file.name} (${e.message ?: e}) - falling back.")
        },
    )

    /**
     * Settings that should survive a restart. Before this the importer
     * remembered nothing at all between launches - every option reset to its
     * default, which would make a camera clock offset worse than useless.
     * Import history itself still lives in the Import NN folders on disk.
     */
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private inner class Saved(private val key: String, private val default: String) {
        private val state = mutableStateOf(prefs.getString(key, default) ?: default)
        operator fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            state.value = value
            prefs.edit().putString(key, value).apply()
        }
    }

    // Per-run, deliberately not persisted: a stale resume point is dangerous.
    var startFilename by mutableStateOf("")
    var startTimestamp by mutableStateOf("")

    var toleranceMinutes by Saved(KEY_TOLERANCE, "30")

    /**
     * Zone the camera's clock was set to, used only when it recorded no
     * OffsetTimeOriginal. Blank means this device's zone - right when you
     * shoot and import in the same place, wrong for a trip imported at home.
     */
    var photoTimeZone by Saved(KEY_PHOTO_ZONE, "")

    /**
     * Camera clock error in minutes, added to every capture time. Negative if
     * the camera runs fast. Blank means no correction.
     */
    var clockOffsetMinutes by Saved(KEY_CLOCK_OFFSET, "")

    /** False only when text has been typed and it is not a known zone id. */
    val photoTimeZoneIsValid: Boolean
        get() = photoTimeZone.trim().let { it.isEmpty() || runCatching { ZoneId.of(it) }.isSuccess }

    private val assumedZone: ZoneId
        get() = photoTimeZone.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()

    private val cameraClockOffset: Duration
        get() = Duration.ofMillis(
            ((clockOffsetMinutes.trim().toDoubleOrNull() ?: 0.0) * 60_000).roundToLong()
        )

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

    init {
        // The track picker used to have to be driven again on every launch;
        // a persisted URI permission makes the last choice stick.
        prefs.getString(KEY_TRACK_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.let { restoreTrack(it) }
    }

    fun selectTrack(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString(KEY_TRACK_URI, uri.toString()).apply()
        loadTrack(uri)
    }

    /**
     * Re-reads a track whose permission was granted in an earlier session.
     * The grant can be gone (revoked, or the file deleted), so a failure here
     * just clears the selection rather than surfacing an error.
     */
    private fun restoreTrack(uri: Uri) {
        loadTrack(uri, onFailure = { clearTrack() })
    }

    private fun loadTrack(uri: Uri, onFailure: (() -> Unit)? = null) {
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
                if (trackUri != uri) return@launch
                if (points.isEmpty() && onFailure != null) onFailure() else trackPoints = points
            }
        }
    }

    fun clearTrack() {
        trackUri = null
        trackName = null
        trackPoints = null
        prefs.edit().remove(KEY_TRACK_URI).apply()
    }

    /**
     * Builds the preview. Nothing is written until [confirmImport]; the plan
     * it produces is the same object execution consumes, so the list on
     * screen cannot disagree with what happens.
     */
    fun startImport() {
        if (running) return
        running = true
        logLines.clear()
        postPhase(Phase.Busy("Preparing…", 0, 0))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                preparePlan()
            } catch (e: Exception) {
                fail("Import failed: ${e.message ?: e}")
            } finally {
                running = false
            }
        }
    }

    fun confirmImport() {
        val planned = phase as? Phase.Planned ?: return
        if (running) return
        running = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                executePlan(planned.plan)
            } catch (e: Exception) {
                fail("Import failed: ${e.message ?: e}")
            } finally {
                running = false
            }
        }
    }

    fun cancelPlan() {
        if (running) return
        postPhase(Phase.Idle)
    }

    /** Includes or excludes one file before the import is confirmed. */
    fun setSelected(source: File, selected: Boolean) {
        val planned = phase as? Phase.Planned ?: return
        val plan = planned.plan
        postPhase(
            Phase.Planned(
                plan.copy(
                    entries = plan.entries.map {
                        if (it.source == source) it.copy(selected = selected) else it
                    }
                )
            )
        )
    }

    private fun preparePlan() {
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

        val captureTime = CaptureTimeResolver(AndroidExifDateReader, assumedZone, cameraClockOffset)
        val geotagger = track?.let { Geotagger(it, tolerance) }
        val importer = PhotoImporter(source, destBase, captureTime, ::log)
        val plan = importer.plan(startFn, startTs, geotagger, gpsWriter)

        if (plan.isEmpty) {
            log("Nothing to import.")
            postPhase(
                Phase.Done(
                    ImportSummary(
                        null, 0, PhotoImporter.describe(emptyList()), track != null, 0, 0, 0
                    )
                )
            )
            return
        }
        pendingContext = PendingImport(importer, geotagger, captureTime, track != null)
        postPhase(Phase.Planned(plan))
    }

    /** Everything [executePlan] needs that the plan itself does not carry. */
    private class PendingImport(
        val importer: PhotoImporter,
        val geotagger: Geotagger?,
        val captureTime: CaptureTimeResolver,
        val hasTrack: Boolean,
    )

    private var pendingContext: PendingImport? = null

    private fun executePlan(plan: ImportPlan) {
        val app = getApplication<Application>()
        val context = pendingContext ?: run {
            fail("The preview expired. Run the import again.")
            return
        }

        postPhase(Phase.Busy("Importing photos", 0, plan.selected.size))
        val result = context.importer.execute(plan) { done, total ->
            postPhase(Phase.Busy("Importing photos", done, total))
        }

        var exifTagged = 0
        var sidecarTagged = 0
        var notTagged = 0
        if (context.geotagger != null) {
            val total = result.copied.size
            postPhase(Phase.Busy("Geotagging", 0, total))
            for ((index, file) in result.copied.withIndex()) {
                val point = context.geotagger.locate(context.captureTime.instantOf(file))
                if (point == null) {
                    log("No track point within tolerance for ${file.name} - not geotagged.")
                    notTagged++
                    postPhase(Phase.Busy("Geotagging", index + 1, total))
                    continue
                }
                // The chain embeds where it can - Lightroom for Android ignores
                // XMP sidecars, so a RAF position has to live in its EXIF block
                // - and falls back to a sidecar for anything else. A file that
                // fails outright is reported and the run continues.
                try {
                    when (gpsWriter.write(file, point)) {
                        is GpsWriteResult.Embedded -> exifTagged++
                        is GpsWriteResult.Sidecar -> sidecarTagged++
                    }
                } catch (e: Exception) {
                    log("Could not geotag ${file.name}: ${e.message ?: e}")
                    notTagged++
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
        pendingContext = null
        postPhase(
            Phase.Done(
                ImportSummary(
                    destFolder = result.destFolder?.name,
                    copied = result.copied.size,
                    breakdown = PhotoImporter.describe(result.copied),
                    geotagAttempted = context.hasTrack,
                    exifTagged = exifTagged,
                    sidecarTagged = sidecarTagged,
                    notTagged = notTagged,
                )
            )
        )
    }

    // --- Tagging photos already imported --------------------------------

    /** Independent of [phase] so a preview on one tab survives the other. */
    var tagPhase by mutableStateOf<Phase>(Phase.Idle)
        private set

    var tagFolder by mutableStateOf<File?>(null)

    /**
     * The Import NN folders on this device. Tagging targets these rather than
     * an arbitrary tree: it is where imports land, it needs no SAF picker, and
     * it keeps everything on java.io.File like the rest of the app.
     */
    fun importFolders(): List<File> {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return base.listFiles { f -> f.isDirectory && f.name.startsWith("Import ") }
            ?.sortedByDescending { it.name }
            .orEmpty()
    }

    fun startTagging() {
        val folder = tagFolder ?: return
        if (running) return
        running = true
        logLines.clear()
        tagPhase = Phase.Busy("Preparing…", 0, 0)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                prepareTagPlan(folder)
            } catch (e: Exception) {
                postTagPhase(Phase.Failed("Tagging failed: ${e.message ?: e}"))
            } finally {
                running = false
            }
        }
    }

    private fun prepareTagPlan(folder: File) {
        val app = getApplication<Application>()
        val uri = trackUri ?: run {
            postTagPhase(Phase.Failed("Select a GPS track first."))
            return
        }
        val track = try {
            app.contentResolver.openInputStream(uri)!!.use { TrackParser.parse(it) }
        } catch (e: Exception) {
            postTagPhase(Phase.Failed("Failed to read track file: ${e.message ?: e}"))
            return
        }
        if (track.isEmpty()) {
            postTagPhase(Phase.Failed("Track file contains no timestamped points."))
            return
        }

        val tolerance = Duration.ofMinutes(toleranceMinutes.trim().toLongOrNull() ?: 30L)
        val geotagger = Geotagger(track, tolerance)
        val captureTime = CaptureTimeResolver(AndroidExifDateReader, assumedZone, cameraClockOffset)

        val files = folder.listFiles { f ->
            f.isFile && f.extension.lowercase() in PhotoImporter.DEFAULT_EXTENSIONS
        }?.sortedBy { it.name }.orEmpty()

        if (files.isEmpty()) {
            postTagPhase(Phase.Failed("No taggable files in ${folder.name}."))
            return
        }

        // Tagging happens in place, so source and destination are the same file.
        val entries = files.map { file ->
            val time = captureTime.instantOf(file)
            val resolved = geotagger.resolve(time)
            val fix = resolved?.point
            PlannedFile(
                source = file,
                destination = file,
                captureTime = time,
                fix = fix,
                gapMeters = resolved?.gapMeters,
                writer = if (fix == null) null else gpsWriter.effectiveWriterFor(file)?.name,
            )
        }
        postTagPhase(Phase.Planned(ImportPlan(folder, folder, null, entries)))
    }

    fun confirmTagging() {
        val planned = tagPhase as? Phase.Planned ?: return
        if (running) return
        running = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                executeTagPlan(planned.plan)
            } catch (e: Exception) {
                postTagPhase(Phase.Failed("Tagging failed: ${e.message ?: e}"))
            } finally {
                running = false
            }
        }
    }

    fun cancelTagging() {
        if (!running) postTagPhase(Phase.Idle)
    }

    fun setTagSelected(source: File, selected: Boolean) {
        val planned = tagPhase as? Phase.Planned ?: return
        val plan = planned.plan
        postTagPhase(
            Phase.Planned(
                plan.copy(
                    entries = plan.entries.map {
                        if (it.source == source) it.copy(selected = selected) else it
                    }
                )
            )
        )
    }

    private fun executeTagPlan(plan: ImportPlan) {
        val app = getApplication<Application>()
        val targets = plan.selected
        var exifTagged = 0
        var sidecarTagged = 0
        var notTagged = 0

        postTagPhase(Phase.Busy("Geotagging", 0, targets.size))
        for ((index, entry) in targets.withIndex()) {
            val fix = entry.fix
            if (fix == null) {
                log("No track point within tolerance for ${entry.source.name} - not geotagged.")
                notTagged++
            } else {
                try {
                    when (gpsWriter.write(entry.source, fix)) {
                        is GpsWriteResult.Embedded -> exifTagged++
                        is GpsWriteResult.Sidecar -> sidecarTagged++
                    }
                } catch (e: Exception) {
                    log("Could not geotag ${entry.source.name}: ${e.message ?: e}")
                    notTagged++
                }
            }
            postTagPhase(Phase.Busy("Geotagging", index + 1, targets.size))
        }

        MediaScannerConnection.scanFile(
            app, targets.map { it.source.path }.toTypedArray(), null, null
        )
        log("Tagged ${exifTagged + sidecarTagged} of ${targets.size} files.")
        postTagPhase(
            Phase.Done(
                ImportSummary(
                    destFolder = plan.destFolder.name,
                    copied = targets.size,
                    breakdown = PhotoImporter.describe(targets.map { it.source }),
                    geotagAttempted = true,
                    exifTagged = exifTagged,
                    sidecarTagged = sidecarTagged,
                    notTagged = notTagged,
                )
            )
        )
    }

    private fun postTagPhase(next: Phase) {
        viewModelScope.launch(Dispatchers.Main.immediate) { tagPhase = next }
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
        const val PREFS = "importer"
        const val KEY_TOLERANCE = "tolerance"
        const val KEY_PHOTO_ZONE = "photoZone"
        const val KEY_CLOCK_OFFSET = "clockOffset"
        const val KEY_TRACK_URI = "trackUri"

        val TIMESTAMP_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
        )
    }
}
