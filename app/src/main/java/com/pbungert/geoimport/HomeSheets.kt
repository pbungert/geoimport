package com.pbungert.geoimport

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pbungert.geoimport.recorder.TrackRecorderService
import java.io.File

// The two sheets the home screen opens: one to start a recording, one to
// pick a folder of photos to place. Both are choices for a run rather than
// settings, so they are shown at the moment they are needed and nowhere else.

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
internal fun RecordSheet(
    viewModel: ImportViewModel,
    onDismiss: () -> Unit,
    onStart: (name: String, minutes: Double) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultName = remember { TrackRecorderService.defaultFileName() }
    val name = rememberTextFieldState()
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
                state = name,
                label = { Text("Name") },
                labelPosition = TextFieldLabelPosition.Above(),
                placeholder = { Text(defaultName) },
                lineLimits = TextFieldLineLimits.SingleLine,
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
                    onStart(name.text.toString(), chosen)
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

/** Picks an "Import NN" folder to place positions into. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickFolderSheet(
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
