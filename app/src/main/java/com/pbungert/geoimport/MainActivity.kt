package com.pbungert.geoimport

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pbungert.geoimport.core.imports.ImportPlan
import com.pbungert.geoimport.core.imports.PlannedFile
import com.pbungert.geoimport.core.model.Track
import com.pbungert.geoimport.recorder.RecordingState
import com.pbungert.geoimport.recorder.TrackRecorderService
import com.pbungert.geoimport.ui.map.DisplayTrack
import com.pbungert.geoimport.ui.map.FullscreenTrackMapDialog
import com.pbungert.geoimport.ui.map.TrackMap
import com.pbungert.geoimport.ui.map.distanceMeters
import com.pbungert.geoimport.ui.map.formatDistance
import com.pbungert.geoimport.ui.theme.GeoimportTheme
import com.pbungert.geoimport.ui.theme.recordRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeoimportTheme {
                GeoimportApp()
            }
        }
    }
}

/**
 * Two screens rather than four tabs. Home is a map with the two things this
 * app does underneath it; everything else is a job you are sent into and come
 * back from.
 */
private enum class Route { Home, Job }

@Composable
fun GeoimportApp(viewModel: ImportViewModel = viewModel()) {
    var showLog by rememberSaveable { mutableStateOf(false) }
    var route by rememberSaveable { mutableStateOf(Route.Home) }
    // The job screen drives two near-identical flows: copying from a card, and
    // placing photos already here. Same panels, different view-model phase, so
    // which one is running is a flag rather than a second screen.
    var placingOnly by rememberSaveable { mutableStateOf(false) }

    val recording by TrackRecorderService.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

    if (showLog) {
        LogScreen(viewModel.logLines) { showLog = false }
        return
    }

    when (route) {
        Route.Home -> HomeScreen(
            viewModel = viewModel,
            recording = recording,
            snackbarHostState = snackbarHostState,
            showMessage = showMessage,
            onShowLog = { showLog = true },
            onOpenJob = { placing ->
                placingOnly = placing
                route = Route.Job
            },
        )
        Route.Job -> JobScreen(
            viewModel = viewModel,
            placingOnly = placingOnly,
            snackbarHostState = snackbarHostState,
            onShowLog = { showLog = true },
            onSwitchToPlacing = { placingOnly = true },
            onClose = { route = Route.Home },
        )
    }
}
// --- Home ----------------------------------------------------------------

/** What a .gpx is, for the media database and for a share. */
private const val GPX_MIME = "application/gpx+xml"

private val TRACK_DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM")

/** The drag handle above the sheet content, which the peek height has to clear. */
private val DRAG_HANDLE_HEIGHT = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
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
    val shown = (if (selected.isEmpty()) ordered.take(1) else ordered.filter { it.name in selected })
        .map { DisplayTrack(it.name, it.track) }
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

/** Newest first, which is the order you think about your own recordings in. */
private fun orderedTracks(viewModel: ImportViewModel): List<ImportViewModel.LoadedTrack> =
    viewModel.tracks.sortedByDescending { it.track.span?.endInclusive ?: Instant.MIN }

/** "Import 06, 48 photos", or null when nothing has ever been imported. */
private fun describeLastImport(folders: List<File>): String? {
    val folder = folders.firstOrNull() ?: return null
    val count = folder.listFiles { f -> f.isFile && !f.name.endsWith(".xmp", ignoreCase = true) }
        ?.size
        ?: return folder.name
    return "${folder.name}, $count photo" + if (count == 1) "" else "s"
}

/** "16 Sep · 8.4 km", or the point count when there is no distance to speak of. */
private fun trackSummary(track: Track, zone: ZoneId): String {
    val day = track.span?.let { TRACK_DAY_FORMAT.format(it.endInclusive.atZone(zone)) }
    val meters = track.distanceMeters
    val detail = if (meters >= 1.0) {
        formatDistance(meters)
    } else {
        "${track.size} point" + if (track.size == 1) "" else "s"
    }
    return listOfNotNull(day, detail).joinToString(" · ")
}

@Composable
private fun HomeOverflow(onShowLog: () -> Unit, onPlaceExisting: () -> Unit) {
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

/**
 * The tracks, and the only list of them there is: the home sheet and the import
 * settings render this same composable off the same state, so ticking a track
 * in one is ticking it in the other.
 *
 * Ticking nothing is the normal case and means "work it out" - the import
 * narrows the whole set to what covers the photos. Ticking some says: only
 * these, on the map and in the next import.
 */
@Composable
private fun TrackList(
    viewModel: ImportViewModel,
    zone: ZoneId,
    showHeader: Boolean,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Null means "take what you need": inside a screen that already scrolls,
    // a scrolling list within a scrolling list fights the finger.
    maxListHeight: Dp? = null,
) {
    val context = LocalContext.current
    val ordered = orderedTracks(viewModel)
    val selected = viewModel.selectedTrackNames
    val active = viewModel.activeTrackNames

    val pickTracks = rememberLauncherForActivityResult(OpenTrackDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addTracks(uris)
    }

    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            ) {
                Text(
                    "Tracks",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (ordered.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            if (selected.isEmpty()) viewModel.selectAllTracks()
                            else viewModel.clearTrackSelection()
                        }
                    ) {
                        Text(if (selected.isEmpty()) "Choose all" else "Clear")
                    }
                }
            }
        }

        val recordedHere = ordered.filter { it.fromRecorder }
        val addedByHand = ordered.filter { !it.fromRecorder }
        // Only worth labelling when there are both kinds to tell apart.
        val grouped = recordedHere.isNotEmpty() && addedByHand.isNotEmpty()

        Column(
            modifier = if (maxListHeight == null) {
                Modifier
            } else {
                Modifier.heightIn(max = maxListHeight).verticalScroll(rememberScrollState())
            },
        ) {
            if (ordered.isEmpty()) {
                Text(
                    if (viewModel.tracksLoading) "Reading tracks…"
                    else "Nothing yet. Tracks you record, and GPX files you add, are listed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            if (grouped) TrackGroupLabel("Recorded")
            for (track in recordedHere + addedByHand) {
                if (grouped && track === addedByHand.firstOrNull()) TrackGroupLabel("Added")
                // Summing a track's length walks every point, so it is worked
                // out once per track rather than on every recomposition.
                val measured = remember(track.key) { trackSummary(track.track, zone) }
                TrackRow(
                    name = track.name,
                    summary = buildString {
                        append(measured)
                        // Once a plan exists it has said which tracks it
                        // matched against; a chosen track it ignored is worth
                        // saying out loud.
                        if (active?.contains(track.name) == false) {
                            append(" · outside this import")
                        }
                    },
                    checked = track.name in selected,
                    onToggle = { viewModel.toggleTrackSelected(track.name) },
                    trailing = {
                        if (track.fromRecorder) {
                            IconButton(onClick = {
                                // The key is the path it was read from - a
                                // recorded folder may hold .kml as well as .gpx,
                                // so the name alone would build a missing file.
                                val file = File(track.key)
                                if (!shareTrack(context, file)) {
                                    onMessage("Nothing here can share a file.")
                                }
                            }) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = "Share ${track.name}",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            IconButton(onClick = { viewModel.removeTrack(track.key) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove ${track.name}",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 20.dp),
        ) {
            OutlinedButton(
                onClick = { pickTracks.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a GPX file", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    if (!openTracksFolder(context)) {
                        onMessage("Tracks are in Documents/GPS-Tracks on this phone.")
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Says which tracks came from where, in place of a switch for the whole folder. */
@Composable
private fun TrackGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun TrackRow(
    name: String,
    summary: String,
    checked: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 12.dp, end = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun RecordingStatus(
    recording: RecordingState,
    distanceMeters: Double?,
    onPauseResume: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording.paused) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

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
                    append(elapsed(now - recording.startedAtMillis))
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

// --- Recording settings --------------------------------------------------

/** Interval presets: label to its value in minutes. */
private val INTERVAL_PRESETS =
    listOf("30 sec" to "0.5", "1 min" to "1", "2 min" to "2", "5 min" to "5")

/**
 * The recording settings, shown the moment they are needed and nowhere else.
 * They are choices for a run rather than preferences - the name is the day you
 * are shooting, the interval is how you are travelling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordSheet(
    viewModel: ImportViewModel,
    onDismiss: () -> Unit,
    onStart: (name: String, minutes: Double) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultName = remember { TrackRecorderService.defaultFileName() }
    var name by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf(viewModel.recordIntervalMinutes) }
    var custom by rememberSaveable {
        mutableStateOf(INTERVAL_PRESETS.none { it.second == interval.trim().replace(',', '.') })
    }

    val minutes = interval.trim().replace(',', '.').toDoubleOrNull()
    val segmentCount = INTERVAL_PRESETS.size + 1

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Record a track", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Keeps logging while the screen is off. Stop it when you are done shooting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text(defaultName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A point every", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    INTERVAL_PRESETS.forEachIndexed { index, (label, value) ->
                        SegmentedButton(
                            selected = !custom && minutes != null && minutes == value.toDouble(),
                            onClick = {
                                custom = false
                                interval = value
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, segmentCount),
                            icon = {},
                        ) { SegmentLabel(label) }
                    }
                    SegmentedButton(
                        selected = custom,
                        onClick = { custom = true },
                        shape = SegmentedButtonDefaults.itemShape(INTERVAL_PRESETS.size, segmentCount),
                        icon = {},
                    ) { SegmentLabel("Custom") }
                }
                AnimatedVisibility(custom) {
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it },
                        label = { Text("Minutes between points") },
                        supportingText = {
                            Text("Any number, fractions included — 0.5 is every thirty seconds.")
                        },
                        singleLine = true,
                        isError = minutes == null || minutes <= 0,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!custom) {
                    Text(
                        "Closer together is more accurate and uses more battery. " +
                            "One a minute suits walking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = {
                    val chosen = minutes ?: return@Button
                    viewModel.recordIntervalMinutes = interval.trim().replace(',', '.')
                    onStart(name, chosen)
                },
                enabled = minutes != null && minutes > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Start recording")
            }
        }
    }
}

/**
 * Label for a segmented button. Segments split the row width evenly, which on
 * a phone is too narrow for a long label like "Custom": it would wrap onto a
 * second line and grow the row. Keep it on one line and shrink the type only
 * as far as the segment demands.
 */
@Composable
private fun SegmentLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 9.sp,
            maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
            stepSize = 0.5.sp,
        ),
    )
}

/** Picks an "Import NN" folder to place positions into. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickFolderSheet(
    folders: List<File>,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Place photos already here", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Writes positions into photos that were imported before — for when a track " +
                    "turned up late, or the camera clock was wrong.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            if (folders.isEmpty()) {
                Text(
                    "No imported folders yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                for (folder in folders) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(folder) }
                            .padding(vertical = 14.dp),
                    ) {
                        Icon(Icons.Filled.SdCard, contentDescription = null)
                        Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/**
 * Opens the tracks folder in whatever browses files on this phone. The one
 * thing the app could not do before: say where the GPX files are in a way you
 * can act on, rather than a path to type in somewhere else.
 *
 * Returns false when nothing on the device answers, which is the caller's cue
 * to fall back to telling the user the path.
 */
private fun openTracksFolder(context: Context): Boolean {
    val dir = TrackRecorderService.tracksDir()
    val relative = dir
        .relativeToOrNull(Environment.getExternalStorageDirectory())
        ?.path?.replace('\\', '/')
        ?: return false
    val folder = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$relative",
    )
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(view) }.isSuccess
}

/** Hands one track to another app - mail, cloud storage, a mapping tool. */
private fun shareTrack(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(GPX_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share ${file.nameWithoutExtension}",
        )
    )
}.isSuccess

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
    MediaScannerConnection.scanFile(context, paths, Array(paths.size) { GPX_MIME }, null)
}

// --- The job screen ------------------------------------------------------

/**
 * One job on screen and nothing competing with it: the map above, and below it
 * whatever this run is currently doing - reviewing, copying, or finished.
 *
 * [placingOnly] drives the same panels off the tagging phase, for photos that
 * are already here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobScreen(
    viewModel: ImportViewModel,
    placingOnly: Boolean,
    snackbarHostState: SnackbarHostState,
    onShowLog: () -> Unit,
    onSwitchToPlacing: () -> Unit,
    onClose: () -> Unit,
) {
    val phase = if (placingOnly) viewModel.tagPhase else viewModel.phase
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    fun leave() {
        // A run in progress keeps going - the notification says so. Anything
        // else is finished with, so its phase is cleared on the way out.
        if (placingOnly) viewModel.cancelTagging() else viewModel.cancelPlan()
        onClose()
    }

    BackHandler { leave() }

    val title = when {
        placingOnly -> viewModel.tagFolder?.name ?: "Place photos"
        phase is Phase.Planned -> phase.plan.destFolder.name
        phase is Phase.Done -> phase.summary.destFolder ?: "Import"
        else -> "Import"
    }

    val active = viewModel.activeTrackNames
    val shown = viewModel.tracks
        .filter { active?.contains(it.name) ?: true }
        .map { DisplayTrack(it.name, it.track) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Import settings")
                    }
                    JobOverflow(onShowLog = onShowLog)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(modifier = Modifier.padding(inner).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TrackMap(
                    shown,
                    modifier = Modifier.fillMaxSize(),
                    onExpand = { fullscreen = true },
                )
                if (shown.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text(
                            "No track covers these photos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.navigationBarsPadding()) {
                    when (phase) {
                        is Phase.Busy -> BusyPanel(phase)
                        is Phase.Planned -> PlanPanel(
                            plan = phase.plan,
                            placing = placingOnly,
                            running = viewModel.running,
                            onToggle = { source, selected ->
                                if (placingOnly) viewModel.setTagSelected(source, selected)
                                else viewModel.setSelected(source, selected)
                            },
                            onCancel = { leave() },
                            onConfirm = {
                                if (placingOnly) viewModel.confirmTagging() else viewModel.confirmImport()
                            },
                        )
                        is Phase.Done -> DonePanel(
                            summary = phase.summary,
                            placing = placingOnly,
                            onPlaceMissing = {
                                val folder = viewModel.importFolders()
                                    .firstOrNull { it.name == phase.summary.destFolder }
                                if (folder != null) {
                                    viewModel.cancelPlan()
                                    viewModel.tagFolder = folder
                                    viewModel.startTagging()
                                    onSwitchToPlacing()
                                }
                            },
                            onDone = { leave() },
                        )
                        is Phase.Failed -> FailedPanel(phase.message, onClose = { leave() })
                        Phase.Idle -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }

    if (fullscreen) {
        FullscreenTrackMapDialog(shown) { fullscreen = false }
    }
    if (showSettings) {
        ImportSettingsSheet(
            viewModel = viewModel,
            showResumePoint = !placingOnly,
            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun JobOverflow(onShowLog: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Import log") },
            onClick = { expanded = false; onShowLog() },
        )
    }
}

/**
 * Work in progress. The count comes before the bar because it is the part
 * worth reading, and the line under it is the early warning: if the number of
 * photos with no track nearby starts climbing, you know now rather than at the
 * summary.
 */
@Composable
private fun BusyPanel(phase: Phase.Busy) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (phase.total > 0) "${phase.label} ${phase.current} of ${phase.total}"
                else "${phase.label}…",
                style = MaterialTheme.typography.headlineSmall,
            )
            phase.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (phase.total > 0) {
            LinearProgressIndicator(
                progress = { phase.current.toFloat() / phase.total },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
        }
        HorizontalDivider()
        Text(
            "You can leave the app — it keeps going and tells you when it is finished.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The preview: one row per file, saying what would happen to it rather than
 * where exactly it would land. Renders the same ImportPlan the run consumes,
 * so what is listed is what happens. Coordinates and writer names are in the
 * log, where they belong when something looks wrong.
 */
@Composable
private fun PlanPanel(
    plan: ImportPlan,
    placing: Boolean,
    running: Boolean,
    onToggle: (File, Boolean) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val count = plan.selected.size
    val missing = count - plan.willGeotag

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "$count photo" + (if (count == 1) "" else "s") +
                    if (placing) " ready to place" else " ready to copy",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                buildString {
                    append("${plan.willGeotag} get a position from your tracks")
                    if (missing > 0) append(" · $missing have no track nearby")
                    if (plan.acrossWideGap > 0) {
                        append(" · ${plan.acrossWideGap} across a gap in the track")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (entry in plan.entries) {
                PlanEntryRow(entry, zone, timeFormat, onToggle)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !running,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onConfirm,
                enabled = !running && count > 0,
                modifier = Modifier.weight(2f).height(52.dp),
            ) {
                Text(
                    (if (placing) "Place " else "Copy ") + "$count photo" +
                        if (count == 1) "" else "s",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One file in a plan: when it was taken, and whether it gets a position. */
@Composable
private fun PlanEntryRow(
    entry: PlannedFile,
    zone: ZoneId,
    timeFormat: DateTimeFormatter,
    onToggle: (File, Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(entry.source, !entry.selected) }
            .padding(vertical = 2.dp),
    ) {
        Checkbox(
            checked = entry.selected,
            onCheckedChange = { onToggle(entry.source, it) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.source.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val taken = entry.captureTime.atZone(zone).format(timeFormat)
            Text(
                if (entry.fix != null) "$taken · placed on your track"
                else "$taken · no track nearby",
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.fix == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The end of a run, in two numbers. How many landed on a track and how many
 * did not is what there is to act on; the EXIF-versus-sidecar split is in the
 * log for when it matters.
 */
@Composable
private fun DonePanel(
    summary: ImportSummary,
    placing: Boolean,
    onPlaceMissing: () -> Unit,
    onDone: () -> Unit,
) {
    val placed = summary.exifTagged + summary.sidecarTagged
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(36.dp),
            )
            Column {
                Text(
                    when {
                        summary.copied == 0 -> "Nothing to import"
                        placing -> "$placed photo" + (if (placed == 1) "" else "s") + " placed"
                        else -> "${summary.copied} photo" +
                            (if (summary.copied == 1) "" else "s") + " copied"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                summary.destFolder?.let {
                    Text(
                        if (placing) it else "into Pictures / $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        if (summary.copied > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!summary.geotagAttempted) {
                    SummaryLine(
                        Icons.Filled.ErrorOutline,
                        colors.error,
                        "No tracks — nothing was placed",
                    )
                } else {
                    if (!placing) {
                        SummaryLine(Icons.Filled.Place, colors.primary, "$placed placed on a track")
                    }
                    if (summary.notTagged > 0) {
                        SummaryLine(
                            Icons.Filled.ErrorOutline,
                            colors.error,
                            "${summary.notTagged} had no track nearby",
                        )
                    }
                }
                if (summary.breakdown.isNotBlank()) {
                    Text(
                        summary.breakdown,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!placing && summary.notTagged > 0 && summary.destFolder != null) {
                OutlinedButton(
                    onClick = onPlaceMissing,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text("Place the ${summary.notTagged}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun SummaryLine(icon: ImageVector, tint: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FailedPanel(message: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
            Text("Stopped", style = MaterialTheme.typography.titleLarge)
        }
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Close")
        }
    }
}

// --- Import settings -----------------------------------------------------

private enum class ResumeMode { Auto, ByFile, ByTime }

/** Which row of the settings sheet is open; at most one at a time. */
private enum class SettingSection { None, Tracks, Resume, Tolerance, Clock }

/**
 * The knobs, reached from the job screen rather than the home screen: this is
 * the moment they matter, because you can see what the current values did and
 * change one if the answer looks wrong. Four rows in plain words, each opening
 * onto the field behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSettingsSheet(
    viewModel: ImportViewModel,
    showResumePoint: Boolean,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var open by rememberSaveable { mutableStateOf(SettingSection.None) }
    var resumeMode by rememberSaveable {
        mutableStateOf(
            when {
                viewModel.startFilename.isNotBlank() -> ResumeMode.ByFile
                viewModel.startTimestamp.isNotBlank() -> ResumeMode.ByTime
                else -> ResumeMode.Auto
            }
        )
    }

    fun toggle(section: SettingSection) {
        open = if (open == section) SettingSection.None else section
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Import settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider()

            SettingRow(
                icon = Icons.Filled.Place,
                title = "Tracks",
                value = when {
                    viewModel.tracksLoading && viewModel.tracks.isEmpty() -> "Reading…"
                    viewModel.tracks.isEmpty() -> "None — nothing will be placed"
                    viewModel.selectedTrackNames.isNotEmpty() ->
                        "${viewModel.selectedTrackNames.size} of ${viewModel.tracks.size} " +
                            "chosen by hand"
                    else -> "${viewModel.tracks.size} available · " +
                        "the ones covering your photos are used"
                },
                expanded = open == SettingSection.Tracks,
                onToggle = { toggle(SettingSection.Tracks) },
            ) {
                // The same list as the home sheet, off the same state: ticking a
                // track here is ticking it there.
                TrackList(
                    viewModel = viewModel,
                    zone = ZoneId.systemDefault(),
                    showHeader = false,
                    onMessage = onMessage,
                )
            }

            HorizontalDivider()

            if (showResumePoint) {
                SettingRow(
                    icon = Icons.Filled.SdCard,
                    title = "Start after",
                    value = when (resumeMode) {
                        ResumeMode.Auto -> "The last photo of the previous import"
                        ResumeMode.ByFile -> viewModel.startFilename.ifBlank { "A filename" }
                        ResumeMode.ByTime -> viewModel.startTimestamp.ifBlank { "A time" }
                    },
                    expanded = open == SettingSection.Resume,
                    onToggle = { toggle(SettingSection.Resume) },
                ) {
                    ResumeEditor(
                        viewModel = viewModel,
                        mode = resumeMode,
                        onModeChange = { mode ->
                            resumeMode = mode
                            // Keep only the field the chosen mode uses, so stale
                            // input can never be applied to the next run.
                            if (mode != ResumeMode.ByFile) viewModel.startFilename = ""
                            if (mode != ResumeMode.ByTime) viewModel.startTimestamp = ""
                        },
                    )
                }
                HorizontalDivider()
            }

            SettingRow(
                icon = Icons.Filled.Tune,
                title = "How far off a track is still fine",
                value = "${viewModel.toleranceMinutes.ifBlank { "30" }} minutes",
                expanded = open == SettingSection.Tolerance,
                onToggle = { toggle(SettingSection.Tolerance) },
            ) {
                OutlinedTextField(
                    value = viewModel.toleranceMinutes,
                    onValueChange = { viewModel.toleranceMinutes = it },
                    label = { Text("Minutes") },
                    supportingText = {
                        Text(
                            "A photo taken this long before or after the track still gets the " +
                                "nearest position on it."
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            SettingRow(
                icon = Icons.Filled.Schedule,
                title = "Camera clock",
                value = describeClock(viewModel),
                expanded = open == SettingSection.Clock,
                onToggle = { toggle(SettingSection.Clock) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = viewModel.clockOffsetMinutes,
                        onValueChange = { viewModel.clockOffsetMinutes = it },
                        label = { Text("Minutes out") },
                        placeholder = { Text("0") },
                        supportingText = {
                            Text("Negative if the camera runs fast. At walking pace two minutes is a couple of streets.")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = viewModel.photoTimeZone,
                        onValueChange = { viewModel.photoTimeZone = it },
                        label = { Text("Time zone it was set to") },
                        placeholder = { Text(ZoneId.systemDefault().id) },
                        supportingText = {
                            Text(
                                if (viewModel.photoTimeZoneIsValid) {
                                    "Only used when the camera records no offset of its own. " +
                                        "Leave blank for this phone's zone."
                                } else {
                                    "Unknown zone — using this phone's zone."
                                }
                            )
                        },
                        isError = !viewModel.photoTimeZoneIsValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HorizontalDivider()

            Text(
                "Only the camera clock usually needs touching, and only if its time was set wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Done")
            }
        }
    }
}

private fun describeClock(viewModel: ImportViewModel): String {
    val offset = viewModel.clockOffsetMinutes.trim().toDoubleOrNull() ?: 0.0
    val zone = viewModel.photoTimeZone.trim()
    val offsetPart = when {
        offset == 0.0 -> "Matches this phone"
        offset > 0 -> "Runs ${trimNumber(offset)} min slow"
        else -> "Runs ${trimNumber(-offset)} min fast"
    }
    return offsetPart + if (zone.isEmpty()) " · same time zone" else " · $zone"
}

/** "2" rather than "2.0", but "2.5" when it matters. */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 14.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        AnimatedVisibility(expanded) {
            Column(modifier = Modifier.padding(start = 24.dp, bottom = 16.dp)) { content() }
        }
    }
}

@Composable
private fun ResumeEditor(
    viewModel: ImportViewModel,
    mode: ResumeMode,
    onModeChange: (ResumeMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = ResumeMode.entries
            modes.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { onModeChange(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = {},
                ) {
                    SegmentLabel(
                        when (entry) {
                            ResumeMode.Auto -> "Auto"
                            ResumeMode.ByFile -> "A file"
                            ResumeMode.ByTime -> "A time"
                        }
                    )
                }
            }
        }
        when (mode) {
            ResumeMode.Auto -> Text(
                "Continues after the newest photo in the last \"Import NN\" folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ResumeMode.ByFile -> OutlinedTextField(
                value = viewModel.startFilename,
                onValueChange = { viewModel.startFilename = it },
                label = { Text("Start after filename") },
                placeholder = { Text("DSCF1234.RAF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ResumeMode.ByTime -> OutlinedTextField(
                value = viewModel.startTimestamp,
                onValueChange = { viewModel.startTimestamp = it },
                label = { Text("Start after") },
                placeholder = { Text("2026-07-17 14:30") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}


// --- Log -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(lines: List<String>, onClose: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Import log") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (lines.isEmpty()) {
                Text(
                    "No log yet. Run an import to see details here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                SelectionContainer(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    LazyColumn(state = listState) {
                        items(lines) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * OpenMultipleDocuments that starts in the app's recorded-tracks folder when
 * at least one recorded track exists there.
 */
private class OpenTrackDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        recordedTracksDirUri()?.let {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it)
        }
        return intent
    }

    private fun recordedTracksDirUri(): Uri? {
        val dir = TrackRecorderService.tracksDir()
        val hasTracks = dir.listFiles()
            ?.any { it.extension.equals("gpx", ignoreCase = true) } == true
        if (!hasTracks) return null
        val relative = dir
            .relativeToOrNull(Environment.getExternalStorageDirectory())
            ?.path?.replace('\\', '/')
            ?: return null
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$relative",
        )
    }
}
