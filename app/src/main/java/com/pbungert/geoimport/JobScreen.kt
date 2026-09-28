package com.pbungert.geoimport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pbungert.geoimport.core.imports.ImportPlan
import com.pbungert.geoimport.core.imports.PlannedFile
import com.pbungert.geoimport.ui.map.DisplayTrack
import com.pbungert.geoimport.ui.map.FullscreenTrackMapDialog
import com.pbungert.geoimport.ui.map.TrackMap
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One job on screen and nothing competing with it: the map above, and below it
 * whatever this run is currently doing - reviewing, copying, or finished.
 *
 * [placingOnly] drives the same panels off the tagging phase, for photos that
 * are already here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobScreen(
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
                            // Placing works on what is already imported, where
                            // leaving a type out has nothing to do with the card.
                            onTypeToggle = if (placingOnly) null else viewModel::setTypeSelected,
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
    onTypeToggle: ((String, Boolean) -> Unit)?,
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

        if (onTypeToggle != null) TypeChips(plan.types, onTypeToggle)

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

/**
 * The file types on the card, each taken or left in one tap. Leaving one out
 * is remembered, and synced, for the next import; a type that is only partly
 * picked counts as taken, and a tap leaves all of it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(types: List<ImportPlan.TypeCount>, onToggle: (String, Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (type in types) {
            val taken = type.selected > 0
            FilterChip(
                selected = taken,
                onClick = { onToggle(type.type, !taken) },
                label = { Text("${type.type.uppercase()} ${type.total}") },
                leadingIcon = if (taken) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
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
