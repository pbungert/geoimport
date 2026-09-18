package com.pbungert.geoimport

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
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
import com.pbungert.geoimport.core.imports.parseResumeTimestamp
import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.core.track.NamedTrack
import com.pbungert.geoimport.core.track.TrackParser
import com.pbungert.geoimport.core.track.TrackSelection
import com.pbungert.geoimport.recorder.TrackRecorderService
import com.pbungert.geoimport.platform.AndroidExifDateReader
import com.pbungert.geoimport.platform.AndroidJpegGpsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KProperty
import kotlin.math.roundToLong

/** Coarse UI state driving the progress bar / summary, separate from the debug log. */
sealed interface Phase {
    data object Idle : Phase

    /** Preview: what the import would do, awaiting confirmation. */
    data class Planned(val plan: ImportPlan) : Phase

    /**
     * [total] == 0 means the work is indeterminate (no count yet). [detail] is
     * the line under the count - the file being handled, or how the placing is
     * going - so a long run says more than a bar creeping along.
     */
    data class Busy(
        val label: String,
        val current: Int,
        val total: Int,
        val detail: String? = null,
    ) : Phase
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
     * Minutes between recorded points, kept across runs: it follows how you
     * travel, not which day it is, so it is nearly always the same as last time.
     */
    var recordIntervalMinutes by Saved(KEY_RECORD_INTERVAL, "1")

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

    /** One track file on offer, already parsed. */
    data class LoadedTrack(
        /** Stable identity: the picked URI, or the path of a recorded file. */
        val key: String,
        val name: String,
        val track: Track,
        /** Recorded by this app, so it came from the folder rather than a pick. */
        val fromRecorder: Boolean,
    )

    /**
     * Every track currently on offer, recorded and picked. Which of them an
     * import actually uses is decided per run from the photos' capture times,
     * because a folder of recordings is mostly days this import knows nothing
     * about - see [TrackSelection].
     */
    var tracks by mutableStateOf<List<LoadedTrack>>(emptyList())
        private set

    var tracksLoading by mutableStateOf(false)
        private set

    /**
     * Names of the tracks the last plan matched against. Null before a plan
     * exists, which is the point at which capture times are known.
     */
    var activeTrackNames by mutableStateOf<Set<String>?>(null)
        private set

    /**
     * Tracks chosen by hand, by name. Empty means "work it out", which is the
     * usual case and the better answer: the import narrows the whole set to
     * what covers the photos, and a folder of recordings is mostly days this
     * import knows nothing about.
     *
     * Deliberately not persisted. A selection made for one trip is wrong for
     * the next, and a stale one would silently leave photos unplaced.
     */
    var selectedTrackNames by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggleTrackSelected(name: String) {
        selectedTrackNames = if (name in selectedTrackNames) {
            selectedTrackNames - name
        } else {
            selectedTrackNames + name
        }
    }

    fun selectAllTracks() {
        selectedTrackNames = tracks.map { it.name }.toSet()
    }

    fun clearTrackSelection() {
        selectedTrackNames = emptySet()
    }

    /** Tracks picked through the document picker, in the order they were added. */
    private var pickedUris: List<Uri> = emptyList()

    /**
     * Whether a run holds the view model. Written only on the main thread,
     * like every other state here: the work goes into `withContext(IO)` rather
     * than the whole coroutine, so the `finally` that clears this runs where
     * Compose can see it.
     */
    var running by mutableStateOf(false)
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    val logLines = mutableStateListOf<String>()

    init {
        pickedUris = readPickedUris()
        refreshTracks()
    }

    fun addTracks(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val added = uris.filter { uri ->
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            uri !in pickedUris
        }
        if (added.isEmpty()) return
        pickedUris = pickedUris + added
        writePickedUris()
        refreshTracks()
    }

    /** Drops a track that was added by hand; recorded ones stay. */
    fun removeTrack(key: String) {
        val uri = pickedUris.firstOrNull { it.toString() == key } ?: return
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickedUris = pickedUris - uri
        writePickedUris()
        refreshTracks()
    }

    /**
     * Parses one file out of the recording folder, or null when it holds
     * nothing usable.
     *
     * The failure is logged rather than swallowed. A parser that rejected
     * every file on Android while the desktop tests stayed green went
     * unnoticed for months, because this said nothing about why.
     */
    private fun loadRecordedTrack(file: File): LoadedTrack? {
        val track = runCatching { file.inputStream().use { TrackParser.parse(it) } }
            .onFailure {
                Log.w("geoimport", "Could not read ${file.name}: ${it.message ?: it}", it)
            }
            .getOrNull()
        if (track == null || track.isEmpty) return null
        return LoadedTrack(file.path, file.nameWithoutExtension, track, true)
    }

    /**
     * Re-reads the single file a recording is appending to.
     *
     * The track being recorded grows by a point every interval, and nothing
     * else in the folder can have changed in between - so [refreshTracks] here
     * would re-parse every recording you have ever made, once per fix, to
     * learn one new point.
     */
    fun refreshRecordingTrack(fileName: String) {
        val file = File(TrackRecorderService.tracksDir(), fileName)
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadRecordedTrack(file) } ?: return@launch
            val index = tracks.indexOfFirst { it.name == loaded.name }
            // Appending covers the first fix of a fresh recording; the list is
            // ordered for display by the caller, so position here is free.
            tracks = if (index < 0) {
                tracks + loaded
            } else {
                tracks.toMutableList().also { it[index] = loaded }
            }
        }
    }

    /**
     * Re-reads everything on offer. A picked file whose grant is gone - revoked,
     * or the file deleted - drops out quietly rather than taking the rest of the
     * selection down with it, which is what the single-track version did.
     */
    fun refreshTracks() {
        val app = getApplication<Application>()
        val picked = pickedUris
        tracksLoading = true
        // The last plan chose from a different set, so its verdict is stale.
        activeTrackNames = null
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = buildList {
                // Everything this app recorded is always on offer. Excluding
                // the folder wholesale used to be a switch; ticking the tracks
                // you want says the same thing without a second kind of "off".
                TrackRecorderService.tracksDir()
                    .listFiles { f -> f.isFile && f.extension.lowercase() in TrackParser.EXTENSIONS }
                    ?.sortedBy { it.name }
                    ?.forEach { file -> loadRecordedTrack(file)?.let(::add) }
                for (uri in picked) {
                    val track = runCatching {
                        app.contentResolver.openInputStream(uri)!!.use { TrackParser.parse(it) }
                    }.getOrNull()
                    if (track != null && !track.isEmpty) {
                        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: uri.toString()
                        add(LoadedTrack(uri.toString(), name.substringBeforeLast('.'), track, false))
                    }
                }
            }
            // Picking a file that is already in the recording folder would
            // otherwise hand the geotagger the same recording twice.
            val distinct = loaded.distinctBy { it.name }
            launch(Dispatchers.Main.immediate) {
                tracks = distinct
                // A track that is gone cannot stay selected, or an import would
                // narrow itself to nothing and quietly place no photos.
                val names = distinct.map { it.name }.toSet()
                selectedTrackNames = selectedTrackNames intersect names
                tracksLoading = false
            }
        }
    }

    private fun readPickedUris(): List<Uri> {
        // Migrates the single-track key so an existing selection is not lost.
        prefs.getString(KEY_TRACK_URI, null)?.let { legacy ->
            prefs.edit()
                .remove(KEY_TRACK_URI)
                .putStringSet(KEY_TRACK_URIS, setOf(legacy))
                .apply()
            return listOfNotNull(runCatching { Uri.parse(legacy) }.getOrNull())
        }
        return prefs.getStringSet(KEY_TRACK_URIS, emptySet())
            .orEmpty()
            .sorted()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun writePickedUris() {
        prefs.edit().putStringSet(KEY_TRACK_URIS, pickedUris.map { it.toString() }.toSet()).apply()
    }

    /** What an import may draw on: the hand-picked set, or everything. */
    private fun namedTracks() = tracks
        .filter { selectedTrackNames.isEmpty() || it.name in selectedTrackNames }
        .map { NamedTrack(it.name, it.track) }

    /**
     * Narrows [tracks] to the ones covering [captureTimes] and builds a
     * geotagger from them, logging what it decided. Null when there is nothing
     * left to match against, which is not an error: the photos still import,
     * and the Tag tab can place them once the right track turns up.
     */
    private fun geotaggerFor(captureTimes: List<Instant>, tolerance: Duration): Geotagger? {
        val available = namedTracks()
        if (available.isEmpty()) {
            log("No track selected — importing without geotagging.")
            postActiveTracks(emptySet())
            return null
        }
        val choice = TrackSelection.choose(available, captureTimes, tolerance)
        choice.lines().forEach(::log)
        postActiveTracks(choice.used.map { it.name }.toSet())
        return choice.used.takeIf { it.isNotEmpty() }?.let { Geotagger(choice.track, tolerance) }
    }

    private fun postActiveTracks(names: Set<String>) {
        viewModelScope.launch(Dispatchers.Main.immediate) { activeTrackNames = names }
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
        activeTrackNames = null
        postPhase(Phase.Busy("Preparing…", 0, 0))
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { preparePlan() }
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
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { executePlan(planned.plan) }
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
            parseResumeTimestamp(it) ?: run {
                fail("Could not parse timestamp '$it'. Use e.g. 2026-07-17 14:30.")
                return
            }
        }
        val tolerance = Duration.ofMinutes(toleranceMinutes.trim().toLongOrNull() ?: 30L)

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
        val importer = PhotoImporter(source, destBase, captureTime, ::log)
        // The track is picked here rather than up front: which recordings are
        // worth matching against only becomes answerable once the photos on the
        // card have been read.
        var geotagger: Geotagger? = null
        val plan = importer.plan(startFn, startTs, gpsWriter) { times ->
            geotaggerFor(times, tolerance).also { geotagger = it }
        }

        if (plan.isEmpty) {
            log("Nothing to import.")
            postPhase(
                Phase.Done(
                    ImportSummary(
                        null, 0, PhotoImporter.describe(emptyList()), geotagger != null, 0, 0, 0
                    )
                )
            )
            return
        }
        pendingContext = PendingImport(importer, geotagger, captureTime, geotagger != null)
        postPhase(Phase.Planned(plan))
    }

    /**
     * Progress while positions are being written. The running count of misses
     * is the useful part: a number climbing here means the tracks do not cover
     * these photos, which is worth knowing before the summary says so.
     */
    private fun placing(done: Int, total: Int, missed: Int) = Phase.Busy(
        label = "Placing positions",
        current = done,
        total = total,
        detail = if (missed > 0) {
            "$missed so far had no track nearby"
        } else {
            "$done placed on a track"
        },
    )

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

        postPhase(Phase.Busy("Copying", 0, plan.selected.size))
        val result = context.importer.execute(plan) { done, total, justCopied ->
            postPhase(Phase.Busy("Copying", done, total, justCopied))
        }

        var exifTagged = 0
        var sidecarTagged = 0
        var notTagged = 0
        if (context.geotagger != null) {
            val total = result.copied.size
            postPhase(Phase.Busy("Placing positions", 0, total))
            for ((index, file) in result.copied.withIndex()) {
                val point = context.geotagger.locate(context.captureTime.instantOf(file))
                if (point == null) {
                    log("No track point within tolerance for ${file.name} - not geotagged.")
                    notTagged++
                    postPhase(placing(index + 1, total, notTagged))
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
                postPhase(placing(index + 1, total, notTagged))
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
        activeTrackNames = null
        tagPhase = Phase.Busy("Preparing…", 0, 0)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { prepareTagPlan(folder) }
            } catch (e: Exception) {
                postTagPhase(Phase.Failed("Tagging failed: ${e.message ?: e}"))
            } finally {
                running = false
            }
        }
    }

    private fun prepareTagPlan(folder: File) {
        val available = namedTracks()
        if (available.isEmpty()) {
            postTagPhase(Phase.Failed("Select a GPS track first."))
            return
        }

        val tolerance = Duration.ofMinutes(toleranceMinutes.trim().toLongOrNull() ?: 30L)
        val captureTime = CaptureTimeResolver(AndroidExifDateReader, assumedZone, cameraClockOffset)

        val files = folder.listFiles { f ->
            f.isFile && f.extension.lowercase() in PhotoImporter.DEFAULT_EXTENSIONS
        }?.sortedBy { it.name }.orEmpty()

        if (files.isEmpty()) {
            postTagPhase(Phase.Failed("No taggable files in ${folder.name}."))
            return
        }

        val timed = files.map { it to captureTime.instantOf(it) }
        val choice = TrackSelection.choose(available, timed.map { it.second }, tolerance)
        choice.lines().forEach(::log)
        postActiveTracks(choice.used.map { it.name }.toSet())
        if (choice.used.isEmpty()) {
            postTagPhase(
                Phase.Failed(
                    choice.lines().joinToString(" ").ifEmpty { "No track covers these photos." }
                )
            )
            return
        }
        val geotagger = Geotagger(choice.track, tolerance)

        // Tagging happens in place, so source and destination are the same file.
        val entries = timed.map { (file, time) ->
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
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { executeTagPlan(planned.plan) }
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
        const val KEY_RECORD_INTERVAL = "recordInterval"

        /** The single-track key, read once at startup and migrated away. */
        const val KEY_TRACK_URI = "trackUri"
        const val KEY_TRACK_URIS = "trackUris"

    }
}
