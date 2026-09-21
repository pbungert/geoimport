package com.pbungert.geoimport

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pbungert.geoimport.core.model.TrackPoint
import com.pbungert.geoimport.core.model.distanceMeters
import com.pbungert.geoimport.core.track.TrackCleanup
import com.pbungert.geoimport.recorder.RecordingState
import com.pbungert.geoimport.recorder.TrackRecorderService
import com.pbungert.geoimport.ui.map.DisplayTrack
import com.pbungert.geoimport.ui.map.TrackMap
import com.pbungert.geoimport.ui.map.formatDistance
import com.pbungert.geoimport.ui.theme.recordRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.math.roundToLong

// The map, what is on it, and the two things this app does.

/** The drag handle above the sheet content, which the peek height has to clear. */
private val DRAG_HANDLE_HEIGHT = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    viewModel: ImportViewModel,
    recording: RecordingState?,
    snackbarHostState: SnackbarHostState,
    showMessage: (String) -> Unit,
    onShowLog: () -> Unit,
    onOpenJob: (placing: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val zone = remember { ZoneId.systemDefault() }

    var lastImport by remember { mutableStateOf<String?>(null) }
    var importFolders by remember { mutableStateOf<List<File>>(emptyList()) }
    var peekContentHeight by remember { mutableStateOf(160.dp) }
    var showRecordSheet by rememberSaveable { mutableStateOf(false) }
    var showPlaceSheet by rememberSaveable { mutableStateOf(false) }
    // The cleaned track is the track. The raw one is evidence, shown on demand
    // by someone asking what the cleanup did - so it is off, and says nothing
    // until it is tapped.
    var showRaw by rememberSaveable { mutableStateOf(false) }
    var explanation by remember { mutableStateOf<String?>(null) }

    // One source for the tracks, the view model's, so the list here and the
    // list in the import settings cannot drift apart. A finished import is what
    // changes which tracks and which folders there are to show.
    LaunchedEffect(viewModel.phase) {
        viewModel.refreshTracks()
        val folders = withContext(Dispatchers.IO) { viewModel.importFolders() }
        importFolders = folders
        lastImport = withContext(Dispatchers.IO) { describeLastImport(folders) }
    }

    // A running recording appends to exactly one file, so between fixes that
    // is the only one worth reading again.
    LaunchedEffect(recording?.fileName, recording?.pointCount) {
        recording?.let { viewModel.refreshRecordingTrack(it.fileName) }
    }

    // Once a launch is enough: this only has to catch up files the recorder
    // could not announce, and it is pointless to repeat it per recorded point.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { scanTracksFolder(context) }
    }

    val ordered = orderedTracks(viewModel)
    val selected = viewModel.selectedTrackNames
    // Nothing chosen shows the newest track, which is what you just recorded.
    val picked = if (selected.isEmpty()) ordered.take(1) else ordered.filter { it.name in selected }
    // Cleaning walks every point, so it is done once per set of tracks rather
    // than on each recomposition. It runs whether or not the raw track is on
    // show, because its result is what gets drawn either way.
    val cleaned = remember(picked.map { it.name }, picked.sumOf { it.track.size }) {
        picked.associate { it.name to TrackCleanup.clean(it.track) }
    }
    val shown = picked.map { loaded ->
        val result = cleaned[loaded.name]
        DisplayTrack(
            name = loaded.name,
            track = result?.track ?: loaded.track,
            underlay = if (showRaw) loaded.track else null,
            marks = if (showRaw) result?.removedPoints.orEmpty() else emptyList(),
        )
    }
    val noteFor = { point: TrackPoint ->
        cleaned.values.firstNotNullOfOrNull { result ->
            result.notes.firstOrNull {
                it.finding == TrackCleanup.Finding.POSITION_EXCURSION && it.point == point
            }
        }
    }
    val liveTrack = recording?.let { rec ->
        ordered.firstOrNull { it.name == rec.fileName.substringBeforeLast('.') }
    }
    // Nothing recorded and nothing imported: a world map would be the worst
    // possible answer to "what is this app for", so it does not get drawn.
    val firstRun = ordered.isEmpty() && !viewModel.tracksLoading && lastImport == null

    fun startRecording(name: String, minutes: Double) {
        context.startForegroundService(
            Intent(context, TrackRecorderService::class.java)
                .setAction(TrackRecorderService.ACTION_START)
                .putExtra(
                    TrackRecorderService.EXTRA_INTERVAL_MILLIS,
                    (minutes * 60_000).roundToLong(),
                )
                .putExtra(TrackRecorderService.EXTRA_FILENAME, name.trim().ifEmpty { null })
        )
        // Without this exemption Doze can suspend GPS updates on long recordings.
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        }
    }

    var pendingStart by remember { mutableStateOf<Pair<String, Double>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val start = pendingStart
        pendingStart = null
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && start != null) {
            startRecording(start.first, start.second)
        }
    }

    fun requestRecording(name: String, minutes: Double) {
        if (!Environment.isExternalStorageManager()) {
            showMessage("Grant \"All files access\", then tap Record again.")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
            return
        }
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            startRecording(name, minutes)
        } else {
            pendingStart = name to minutes
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun send(action: String) {
        context.startService(Intent(context, TrackRecorderService::class.java).setAction(action))
    }

    fun beginImport() {
        // An import already under way is something to return to, not to start
        // again - the button says so and this makes it true.
        if (viewModel.running || viewModel.phase !is Phase.Idle) {
            onOpenJob(false)
            return
        }
        if (!Environment.isExternalStorageManager()) {
            showMessage("Grant \"All files access\", then tap Import again.")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
            return
        }
        viewModel.startImport()
        onOpenJob(false)
    }

    // The peek is measured from the bottom edge, which is under the navigation
    // bar: without its height in here the two buttons are cut in half.
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekContentHeight + DRAG_HANDLE_HEIGHT + navBarHeight,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize(),
        sheetContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // What shows while the sheet is down; its height sets the peek,
                // so the two can never disagree.
                Box(
                    modifier = Modifier.onSizeChanged {
                        peekContentHeight = with(density) { it.height.toDp() }
                    }
                ) {
                    HomePeek(
                        viewModel = viewModel,
                        recording = recording,
                        firstRun = firstRun,
                        trackCount = ordered.size,
                        selectedCount = selected.size,
                        shownName = shown.singleOrNull()?.name,
                        lastImport = lastImport,
                        sheetCollapsed = sheetState.currentValue == SheetValue.PartiallyExpanded,
                        liveDistanceMeters = liveTrack?.track?.distanceMeters,
                        onRecord = { showRecordSheet = true },
                        onStop = { send(TrackRecorderService.ACTION_STOP) },
                        onPauseResume = {
                            send(
                                if (recording?.paused == true) TrackRecorderService.ACTION_RESUME
                                else TrackRecorderService.ACTION_PAUSE
                            )
                        },
                        onImport = { beginImport() },
                    )
                }

                HorizontalDivider()

                TrackList(
                    viewModel = viewModel,
                    zone = zone,
                    showHeader = true,
                    onMessage = showMessage,
                    // As much of the screen as the sheet can give without the
                    // buttons above it sliding out of reach.
                    maxListHeight = (screenHeight * 0.52f),
                )
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (firstRun) {
                FirstRunPanel(modifier = Modifier.fillMaxSize().padding(inner))
            } else {
                // No expand button here: the map already fills the screen, and
                // the one inside TrackMap would sit under the status bar.
                TrackMap(
                    shown,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = inner,
                    onMarkClick = { point ->
                        val note = noteFor(point)
                        explanation = if (note == null) {
                            "This fix was removed from the track."
                        } else {
                            "Removed at " +
                                note.time.atZone(zone).toLocalTime().withNano(0) +
                                " — ${note.detail}. A step that far out and " +
                                "straight back is not a route anybody took."
                        }
                    },
                    onUnderlayClick = {
                        val removed = cleaned.values.sumOf { it.removedCount }
                        val altitudes = cleaned.values.sumOf {
                            it.count(TrackCleanup.Finding.ELEVATION_UNTRUSTED) +
                                it.count(TrackCleanup.Finding.ELEVATION_EXCURSION)
                        }
                        explanation = "Grey is the track as recorded. " +
                            "$removed fix" + (if (removed == 1) "" else "es") + " removed" +
                            (if (altitudes > 0) ", $altitudes altitude" +
                                (if (altitudes == 1) "" else "s") + " dropped" else "") +
                            ". Tap a red ring to see why that one went."
                    },
                )
                if (shown.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text(
                            if (viewModel.tracksLoading) "Reading tracks…"
                            else "No track to show here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                // Only ever here because something on the map was tapped, and
                // gone again on the next tap: an answer to a question, not a
                // caption sitting over a view that reads fine without one.
                explanation?.let { text ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(16.dp)
                            // Stops short of the overflow button in the same
                            // row, which the text would otherwise run under.
                            .padding(end = 56.dp)
                            .clickable { explanation = null },
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Nothing floats over the map but the menu. What is drawn is named
            // in the sheet, a thumb's width below, so a chip saying the same
            // thing was one element too many.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
            ) {
                HomeOverflow(
                    onShowLog = onShowLog,
                    onPlaceExisting = { showPlaceSheet = true },
                    showRaw = showRaw,
                    onToggleRaw = {
                        showRaw = !showRaw
                        // The explanation belongs to the grey line; without it
                        // on screen there is nothing for it to be about.
                        explanation = null
                    },
                )
            }
        }
    }

    if (showRecordSheet) {
        RecordSheet(
            viewModel = viewModel,
            onDismiss = { showRecordSheet = false },
            onStart = { name, minutes ->
                showRecordSheet = false
                requestRecording(name, minutes)
            },
        )
    }
    if (showPlaceSheet) {
        PickFolderSheet(
            folders = importFolders,
            onDismiss = { showPlaceSheet = false },
            onPick = { folder ->
                showPlaceSheet = false
                viewModel.tagFolder = folder
                viewModel.startTagging()
                onOpenJob(true)
            },
        )
    }
}

/** "Import 06, 48 photos", or null when nothing has ever been imported. */
private fun describeLastImport(folders: List<File>): String? {
    val folder = folders.firstOrNull() ?: return null
    val count = folder.listFiles { f -> f.isFile && !f.name.endsWith(".xmp", ignoreCase = true) }
        ?.size
        ?: return folder.name
    return "${folder.name}, $count photo" + if (count == 1) "" else "s"
}

@Composable
private fun HomeOverflow(
    onShowLog: () -> Unit,
    onPlaceExisting: () -> Unit,
    showRaw: Boolean,
    onToggleRaw: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            modifier = Modifier.size(44.dp).clickable { expanded = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (showRaw) "Hide raw track" else "Show raw track") },
                leadingIcon = {
                    if (showRaw) Icon(Icons.Filled.Check, contentDescription = null)
                },
                onClick = { expanded = false; onToggleRaw() },
            )
            DropdownMenuItem(
                text = { Text("Place photos already here") },
                onClick = { expanded = false; onPlaceExisting() },
            )
            DropdownMenuItem(
                text = { Text("Import log") },
                onClick = { expanded = false; onShowLog() },
            )
        }
    }
}

/**
 * What the sheet shows while it is down: what is on the map, the two jobs, and
 * - because nothing floats over the map any more - the name of the track being
 * drawn. While a recording runs the left button becomes Stop and the line
 * becomes the run itself, the only time this app has something to report live.
 */
@Composable
private fun HomePeek(
    viewModel: ImportViewModel,
    recording: RecordingState?,
    firstRun: Boolean,
    trackCount: Int,
    selectedCount: Int,
    shownName: String?,
    lastImport: String?,
    sheetCollapsed: Boolean,
    liveDistanceMeters: Double?,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onPauseResume: () -> Unit,
    onImport: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val importing = viewModel.running || viewModel.phase !is Phase.Idle

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (recording != null) {
            RecordingStatus(recording, liveDistanceMeters, onPauseResume)
        } else if (!firstRun) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        viewModel.tracksLoading && trackCount == 0 -> "Reading tracks…"
                        trackCount == 0 -> "No tracks yet"
                        selectedCount > 0 ->
                            "$selectedCount of $trackCount track" +
                                (if (trackCount == 1) "" else "s") + " chosen"
                        shownName != null -> shownName
                        else -> "$trackCount track" + if (trackCount == 1) "" else "s"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        trackCount == 0 -> "Photos would be copied without a position."
                        sheetCollapsed -> "Pull up to choose which tracks to use"
                        selectedCount > 0 -> "Only these will be used for the next import"
                        lastImport != null -> "Last import $lastImport"
                        else -> "The ones covering your photos are used"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (recording != null) {
                ActionPill(
                    label = "Stop",
                    icon = Icons.Filled.Stop,
                    container = colors.errorContainer,
                    content = colors.onErrorContainer,
                    iconTint = colors.error,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ActionPill(
                    label = "Record",
                    icon = Icons.Filled.FiberManualRecord,
                    container = colors.primaryContainer,
                    content = colors.onPrimaryContainer,
                    // The red dot is what makes this the record button at a
                    // glance, so it gets a red of its own rather than the
                    // muted one that carries error text in the dark theme.
                    iconTint = colors.recordRed,
                    onClick = onRecord,
                    modifier = Modifier.weight(1f),
                )
            }
            // Both green and the same weight: opening the app says nothing
            // about which of the two you came to do.
            ActionPill(
                label = if (importing) "Importing…" else "Import",
                icon = Icons.Filled.SdCard,
                container = colors.primaryContainer,
                content = colors.onPrimaryContainer,
                iconTint = colors.onPrimaryContainer,
                onClick = onImport,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RecordingStatus(
    recording: RecordingState,
    distanceMeters: Double?,
    onPauseResume: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Nothing moves while paused, so the ticker stops with the recording.
    LaunchedEffect(recording.paused) {
        while (!recording.paused) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // A pause stops the clock rather than merely stopping the fixes: the
    // reading is how long was recorded, not how long ago you started. Reading
    // up to pausedAtMillis also keeps a stale `now` out of the sum.
    val elapsedMillis =
        (recording.pausedAtMillis ?: now) - recording.startedAtMillis - recording.pausedTotalMillis

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = if (recording.paused) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.recordRed,
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildString {
                    append(elapsed(elapsedMillis))
                    if (distanceMeters != null) append(" · ${formatDistance(distanceMeters)}")
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                buildString {
                    append(recording.fileName.substringBeforeLast('.'))
                    append(" · ${recording.pointCount} points")
                    if (recording.droppedCount > 0) append(" · ${recording.droppedCount} dropped")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onPauseResume) {
            Icon(
                if (recording.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (recording.paused) "Resume" else "Pause")
        }
    }
}

/** "1:24:06", or "4:31" under an hour - no leading zero hour to read past. */
private fun elapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun ActionPill(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.height(60.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the screen says before anything has been recorded. A world map would be
 * the worst possible answer to "what is this for", so it is replaced by the
 * three sentences that answer it.
 */
@Composable
private fun FirstRunPanel(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(72.dp),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Nothing recorded yet", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Your tracks will show up here on the map.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FirstRunStep(1, "Tap Record before you start shooting. The phone logs where you go.")
                FirstRunStep(2, "Back home, put the card in and tap Import.")
                FirstRunStep(3, "Each photo gets the position you were at when you took it.")
            }
        }
    }
}

@Composable
private fun FirstRunStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

/**
 * Tells the media database about every track it may not know yet. The recorder
 * only announced a file when a run ended, so anything older - or from a run the
 * system killed - stayed invisible to file managers and to a PC over USB.
 * Rescanning is cheap and idempotent, and it also corrects the type of files
 * indexed earlier as anonymous bytes.
 */
private fun scanTracksFolder(context: Context) {
    val paths = TrackRecorderService.tracksDir()
        .listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
        ?.map { it.path }
        ?.toTypedArray()
        ?: return
    if (paths.isEmpty()) return
    MediaScannerConnection.scanFile(
        context, paths, Array(paths.size) { TrackRecorderService.GPX_MIME }, null,
    )
}
