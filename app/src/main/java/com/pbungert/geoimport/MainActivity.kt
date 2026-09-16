package com.pbungert.geoimport

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.CircularProgressIndicator
import com.pbungert.geoimport.core.track.TrackParser
import com.pbungert.geoimport.recorder.RecordingState
import com.pbungert.geoimport.recorder.TrackRecorderService
import com.pbungert.geoimport.ui.map.DisplayTrack
import com.pbungert.geoimport.ui.map.FullscreenTrackMapDialog
import com.pbungert.geoimport.ui.map.TrackMap
import com.pbungert.geoimport.ui.theme.GeoimportTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pbungert.geoimport.core.imports.ImportPlan
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeoimportTheme {
                ImportScreen()
            }
        }
    }
}

private enum class MainTab { Record, Import, Tag, Map }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: ImportViewModel = viewModel()) {
    var showLog by rememberSaveable { mutableStateOf(false) }

    if (showLog) {
        LogScreen(viewModel.logLines) { showLog = false }
        return
    }

    val recording by TrackRecorderService.state.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Import) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            MainTab.Record -> "Record GPS track"
                            MainTab.Import -> "Import photos"
                            MainTab.Tag -> "Geotag imported photos"
                            MainTab.Map -> "Recorded tracks"
                        }
                    )
                },
                actions = { OverflowMenu(onShowLog = { showLog = true }) },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == MainTab.Record,
                    onClick = { selectedTab = MainTab.Record },
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text("Record") },
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.Import,
                    onClick = { selectedTab = MainTab.Import },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    label = { Text("Import") },
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.Tag,
                    onClick = { selectedTab = MainTab.Tag },
                    icon = { Icon(Icons.Filled.EditLocationAlt, contentDescription = null) },
                    label = { Text("Tag") },
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.Map,
                    onClick = { selectedTab = MainTab.Map },
                    icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    label = { Text("Map") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            AnimatedVisibility(recording != null) {
                recording?.let { RecordingBanner(it) }
            }
            when (selectedTab) {
                MainTab.Record -> RecordTab(recording, showMessage, modifier = Modifier.fillMaxSize())
                MainTab.Import -> ImportTab(viewModel, showMessage, modifier = Modifier.fillMaxSize())
                MainTab.Tag -> TagTab(viewModel, modifier = Modifier.fillMaxSize())
                MainTab.Map -> MapTab(recording, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// --- Recording indicator -------------------------------------------------

@Composable
private fun RecordingBanner(recording: RecordingState) {
    val context = LocalContext.current
    fun send(action: String) {
        context.startService(
            Intent(context, TrackRecorderService::class.java).setAction(action)
        )
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (recording.paused) Icons.Filled.Pause else Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = if (recording.paused) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (recording.paused) "GPS track paused" else "Recording GPS track",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    buildString {
                        append("${recording.fileName} · ${recording.pointCount} points")
                        if (recording.droppedCount > 0) append(" · ${recording.droppedCount} dropped")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = {
                send(
                    if (recording.paused) TrackRecorderService.ACTION_RESUME
                    else TrackRecorderService.ACTION_PAUSE
                )
            }) {
                Icon(
                    if (recording.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (recording.paused) "Resume" else "Pause")
            }
            TextButton(onClick = { send(TrackRecorderService.ACTION_STOP) }) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stop")
            }
        }
    }
}

// --- Record tab ----------------------------------------------------------

/** Interval presets shown as segments: label to its value in minutes. */
private val INTERVAL_PRESETS = listOf("30s" to "0.5", "1m" to "1", "2m" to "2", "5m" to "5")

/**
 * Label for a segmented button. Segments split the row width evenly, which on a phone is
 * too narrow for a long label like "Custom": it would wrap onto a second line and grow the
 * row. Keep it on one line and shrink the type only as far as the segment demands, so the
 * short labels stay at their normal size on wider screens.
 */
@Composable
private fun SegmentLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        // Below the floor (tiny screen plus a large system font scale) the label
        // ellipsizes rather than being cut mid-glyph.
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 9.sp,
            maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
            stepSize = 0.5.sp,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordTab(
    recording: RecordingState?,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var intervalMinutes by rememberSaveable { mutableStateOf("1") }
    var customInterval by rememberSaveable { mutableStateOf(false) }
    var fileName by rememberSaveable { mutableStateOf("") }
    val defaultName = remember { TrackRecorderService.defaultFileName() }

    fun startRecording() {
        context.startForegroundService(
            Intent(context, TrackRecorderService::class.java)
                .setAction(TrackRecorderService.ACTION_START)
                .putExtra(
                    TrackRecorderService.EXTRA_INTERVAL_MILLIS,
                    intervalMinutes.trim().replace(',', '.').toDoubleOrNull()
                        ?.let { (it * 60_000).roundToLong() }
                        ?: TrackRecorderService.DEFAULT_INTERVAL_MILLIS,
                )
                .putExtra(TrackRecorderService.EXTRA_FILENAME, fileName.trim().ifEmpty { null })
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRecording()
    }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Logs your position to a GPX file in the background. Use it to record a track " +
                    "while shooting, then geotag the photos on the Import tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("Track filename") },
                placeholder = { Text(defaultName) },
                supportingText = { Text("Leave empty to use the default name") },
                singleLine = true,
                enabled = recording == null,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Interval", style = MaterialTheme.typography.labelMedium)
            val current = intervalMinutes.trim().replace(',', '.').toDoubleOrNull()
            val segmentCount = INTERVAL_PRESETS.size + 1
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                INTERVAL_PRESETS.forEachIndexed { index, (label, value) ->
                    SegmentedButton(
                        selected = !customInterval && current != null && current == value.toDouble(),
                        onClick = {
                            customInterval = false
                            intervalMinutes = value
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, segmentCount),
                        icon = {},
                        enabled = recording == null,
                    ) { SegmentLabel(label) }
                }
                SegmentedButton(
                    selected = customInterval,
                    onClick = { customInterval = true },
                    shape = SegmentedButtonDefaults.itemShape(INTERVAL_PRESETS.size, segmentCount),
                    icon = {},
                    enabled = recording == null,
                ) { SegmentLabel("Custom") }
            }
            AnimatedVisibility(customInterval) {
                OutlinedTextField(
                    value = intervalMinutes,
                    onValueChange = { intervalMinutes = it },
                    label = { Text("Interval (min)") },
                    singleLine = true,
                    enabled = recording == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        LiveTrackMap(
            recording,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Button(
            onClick = {
                if (recording != null) {
                    context.startService(
                        Intent(context, TrackRecorderService::class.java)
                            .setAction(TrackRecorderService.ACTION_STOP)
                    )
                } else if (!Environment.isExternalStorageManager()) {
                    showMessage("Grant \"All files access\", then tap Record again.")
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                } else {
                    val missing = listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ).filter {
                        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) startRecording()
                    else permissionLauncher.launch(missing.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Icon(
                if (recording != null) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (recording != null) "Stop recording" else "Record GPS track")
        }
    }
}

/**
 * Map of the recording in progress, re-read from its GPX file as points
 * arrive. Shows a hint instead of a track while idle or before the first fix.
 */
@Composable
private fun LiveTrackMap(recording: RecordingState?, modifier: Modifier = Modifier) {
    var track by remember { mutableStateOf<DisplayTrack?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(recording?.fileName, recording?.pointCount) {
        track = withContext(Dispatchers.IO) {
            recording?.let { rec ->
                val file = File(TrackRecorderService.tracksDir(), rec.fileName)
                try {
                    file.inputStream().use { TrackParser.parse(it) }
                        .takeIf { it.isNotEmpty() }
                        ?.let { DisplayTrack(file.nameWithoutExtension, it) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    val tracks = listOfNotNull(track)
    Box(modifier = modifier.clip(MaterialTheme.shapes.medium)) {
        TrackMap(tracks, modifier = Modifier.fillMaxSize(), onExpand = { fullscreen = true })
        if (tracks.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    if (recording == null) "Your track will appear here while recording."
                    else "Waiting for the first GPS fix…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
    if (fullscreen) {
        FullscreenTrackMapDialog(tracks) { fullscreen = false }
    }
}

// --- Map tab -------------------------------------------------------------

@Composable
private fun MapTab(recording: RecordingState?, modifier: Modifier = Modifier) {
    var tracks by remember { mutableStateOf<List<DisplayTrack>?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // Reload whenever the active recording grows so the live track stays current.
    LaunchedEffect(recording?.fileName, recording?.pointCount) {
        tracks = withContext(Dispatchers.IO) { loadRecordedTracks() }
    }

    Box(modifier = modifier) {
        val loaded = tracks
        when {
            loaded == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            loaded.isEmpty() -> Text(
                "No recorded tracks yet.\nRecord a GPS track and it will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> TrackMap(
                loaded,
                modifier = Modifier.fillMaxSize(),
                onExpand = { fullscreen = true },
            )
        }
    }
    if (fullscreen) {
        FullscreenTrackMapDialog(tracks.orEmpty()) { fullscreen = false }
    }
}

/** Parses all recorded GPX files, newest first, skipping unreadable ones. */
private fun loadRecordedTracks(): List<DisplayTrack> =
    TrackRecorderService.tracksDir()
        .listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { file ->
            try {
                file.inputStream().use { TrackParser.parse(it) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { DisplayTrack(file.nameWithoutExtension, it) }
            } catch (_: Exception) {
                null
            }
        }
        .orEmpty()

// --- Import tab ----------------------------------------------------------

private enum class ResumeMode { Auto, ByFile, ByTime }

@Composable
private fun ImportTab(
    viewModel: ImportViewModel,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var resumeMode by rememberSaveable { mutableStateOf(ResumeMode.Auto) }

    val pickTrack = rememberLauncherForActivityResult(OpenTrackDocument()) { uri ->
        uri?.let(viewModel::selectTrack)
    }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Track selector
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pickTrack.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        viewModel.trackName ?: "Select GPS track…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (viewModel.trackUri != null) {
                    TextButton(onClick = viewModel::clearTrack) { Text("Clear") }
                }
            }
            Text(
                if (viewModel.trackUri != null) "Photos will be geotagged from this track."
                else "No track selected — photos will be imported without geotagging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val trackPoints = viewModel.trackPoints
            if (viewModel.trackUri != null && !trackPoints.isNullOrEmpty()) {
                var mapFullscreen by rememberSaveable { mutableStateOf(false) }
                val displayTracks = listOf(
                    DisplayTrack(viewModel.trackName ?: "Selected track", trackPoints)
                )
                TrackMap(
                    displayTracks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(MaterialTheme.shapes.medium),
                    onExpand = { mapFullscreen = true },
                )
                if (mapFullscreen) {
                    FullscreenTrackMapDialog(displayTracks) { mapFullscreen = false }
                }
            }

            AdvancedOptions(
                open = advancedOpen,
                onToggle = { advancedOpen = !advancedOpen },
                resumeMode = resumeMode,
                onResumeModeChange = { mode ->
                    resumeMode = mode
                    // Keep only the field the chosen mode uses so stale input is never applied.
                    if (mode != ResumeMode.ByFile) viewModel.startFilename = ""
                    if (mode != ResumeMode.ByTime) viewModel.startTimestamp = ""
                },
                viewModel = viewModel,
            )

            when (val phase = viewModel.phase) {
                is Phase.Planned -> PlanPreview(
                    plan = phase.plan,
                    onToggle = { source, selected -> viewModel.setSelected(source, selected) },
                )
                is Phase.Busy -> ProgressSection(phase)
                is Phase.Done -> SummaryCard(phase.summary)
                is Phase.Failed -> FailureCard(phase.message)
                Phase.Idle -> EmptyState()
            }
        }

        val planned = viewModel.phase as? Phase.Planned
        if (planned == null) {
            Button(
                onClick = {
                    if (!Environment.isExternalStorageManager()) {
                        showMessage("Grant \"All files access\", then tap Import again.")
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.fromParts("package", context.packageName, null),
                            )
                        )
                    } else {
                        viewModel.startImport()
                    }
                },
                enabled = !viewModel.running,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Filled.SdCard, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (viewModel.running) "Preparing…" else "Import from SD card")
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.cancelPlan() },
                    enabled = !viewModel.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { viewModel.confirmImport() },
                    enabled = !viewModel.running && planned.plan.selected.isNotEmpty(),
                    modifier = Modifier.weight(2f),
                ) {
                    Icon(Icons.Filled.SdCard, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import ${planned.plan.selected.size} files")
                }
            }
        }
    }
}

/**
 * The preview: one row per file with the position it would be tagged with and
 * the writer that would handle it. Renders the same ImportPlan the import
 * consumes, so what is listed is exactly what happens.
 */
@Composable
private fun PlanPreview(
    plan: ImportPlan,
    onToggle: (File, Boolean) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                "Into \"${plan.destFolder.name}\"",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                "${plan.willGeotag} of ${plan.selected.size} would be geotagged",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))

            for (entry in plan.entries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(entry.source, !entry.selected) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Checkbox(
                        checked = entry.selected,
                        onCheckedChange = { onToggle(entry.source, it) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.source.name, style = MaterialTheme.typography.bodyMedium)
                        val taken = entry.captureTime.atZone(zone).format(timeFormat)
                        val where = entry.fix?.let {
                            "%.5f, %.5f".format(it.lat, it.lon) +
                                (entry.writer?.let { w -> "  [$w]" } ?: "")
                        } ?: "no track point within tolerance"
                        Text(
                            "$taken  $where",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.fix == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Geotags photos that are already in an Import NN folder - for when a track
 * was recorded but not selected at import time, or the camera clock was off.
 */
@Composable
private fun TagTab(viewModel: ImportViewModel, modifier: Modifier = Modifier) {
    val folders = remember(viewModel.tagPhase) { viewModel.importFolders() }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Writes positions into photos already on this device. " +
                    "Uses the track, tolerance and clock offset from the Import tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                viewModel.trackName?.let { "Track: $it" } ?: "No track selected - pick one on the Import tab.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (folders.isEmpty()) {
                Text("No \"Import NN\" folders found.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Folder", style = MaterialTheme.typography.labelMedium)
                for (folder in folders) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.tagFolder = folder },
                    ) {
                        RadioButton(
                            selected = viewModel.tagFolder == folder,
                            onClick = { viewModel.tagFolder = folder },
                        )
                        Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            when (val phase = viewModel.tagPhase) {
                is Phase.Planned -> PlanPreview(
                    plan = phase.plan,
                    onToggle = { source, selected -> viewModel.setTagSelected(source, selected) },
                )
                is Phase.Busy -> ProgressSection(phase)
                is Phase.Done -> SummaryCard(phase.summary)
                is Phase.Failed -> FailureCard(phase.message)
                Phase.Idle -> Unit
            }
        }

        val planned = viewModel.tagPhase as? Phase.Planned
        if (planned == null) {
            Button(
                onClick = { viewModel.startTagging() },
                enabled = !viewModel.running && viewModel.tagFolder != null && viewModel.trackName != null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Filled.EditLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Preview tagging")
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.cancelTagging() },
                    enabled = !viewModel.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { viewModel.confirmTagging() },
                    enabled = !viewModel.running && planned.plan.selected.isNotEmpty(),
                    modifier = Modifier.weight(2f),
                ) {
                    Text("Tag ${planned.plan.selected.size} files")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedOptions(
    open: Boolean,
    onToggle: () -> Unit,
    resumeMode: ResumeMode,
    onResumeModeChange: (ResumeMode) -> Unit,
    viewModel: ImportViewModel,
) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Advanced options", modifier = Modifier.weight(1f))
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (open) "Collapse" else "Expand",
        )
    }

    AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Resume point", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = ResumeMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = resumeMode == mode,
                        onClick = { onResumeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        icon = {},
                    ) {
                        Text(
                            when (mode) {
                                ResumeMode.Auto -> "Auto"
                                ResumeMode.ByFile -> "By file"
                                ResumeMode.ByTime -> "By time"
                            }
                        )
                    }
                }
            }

            when (resumeMode) {
                ResumeMode.Auto -> Text(
                    "Continues after the newest file in the last \"Import NN\" folder.",
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
                    label = { Text("Start after timestamp") },
                    placeholder = { Text("2026-07-17 14:30") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = viewModel.toleranceMinutes,
                onValueChange = { viewModel.toleranceMinutes = it },
                label = { Text("Geotag tolerance (minutes)") },
                supportingText = {
                    Text("How far outside the track a photo may still be placed.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = viewModel.clockOffsetMinutes,
                onValueChange = { viewModel.clockOffsetMinutes = it },
                label = { Text("Camera clock offset (minutes)") },
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
                label = { Text("Camera time zone") },
                placeholder = { Text(ZoneId.systemDefault().id) },
                supportingText = {
                    Text(
                        if (viewModel.photoTimeZoneIsValid) {
                            "Only used when the camera records no time offset. Leave blank for this device's zone."
                        } else {
                            "Unknown zone - using this device's zone."
                        }
                    )
                },
                isError = !viewModel.photoTimeZoneIsValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.SdCard,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Insert an SD card and tap Import",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Shared pieces -------------------------------------------------------

@Composable
private fun ProgressSection(phase: Phase.Busy) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val counts = if (phase.total > 0) " ${phase.current} / ${phase.total}" else "…"
        Text("${phase.label}$counts", style = MaterialTheme.typography.bodyMedium)
        if (phase.total > 0) {
            LinearProgressIndicator(
                progress = { phase.current.toFloat() / phase.total },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SummaryCard(summary: ImportSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (summary.copied == 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    Text("Nothing to import", style = MaterialTheme.typography.titleMedium)
                }
                return@Column
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("Import complete", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                buildString {
                    append("${summary.copied} files")
                    summary.destFolder?.let { append(" → $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                summary.breakdown,
                style = MaterialTheme.typography.bodySmall,
            )
            val geotagLine = when {
                !summary.geotagAttempted -> "No track selected — not geotagged"
                else -> buildString {
                    append("Geotagged ${summary.exifTagged + summary.sidecarTagged} of ${summary.copied}")
                    append(" (${summary.exifTagged} EXIF · ${summary.sidecarTagged} sidecar)")
                    if (summary.notTagged > 0) append(" · ${summary.notTagged} skipped")
                }
            }
            Text(geotagLine, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FailureCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Import stopped",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun OverflowMenu(onShowLog: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Import Log") },
            onClick = {
                expanded = false
                onShowLog()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(lines: List<String>, onClose: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Import Log") },
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
 * OpenDocument that starts in the app's recorded-tracks folder when at least
 * one recorded track exists there.
 */
private class OpenTrackDocument : ActivityResultContracts.OpenDocument() {
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
