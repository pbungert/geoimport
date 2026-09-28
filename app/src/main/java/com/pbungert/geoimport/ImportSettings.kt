package com.pbungert.geoimport

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.ZoneId

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
internal fun ImportSettingsSheet(
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
                "Continues after the newest photo in the last \"Import NN\" folder, or " +
                    "after an import on another device that got further on this card.",
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
