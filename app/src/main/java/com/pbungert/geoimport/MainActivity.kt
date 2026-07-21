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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pbungert.geoimport.recorder.TrackRecorderService
import com.pbungert.geoimport.ui.theme.GeoimportTheme
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: ImportViewModel = viewModel()) {
    val context = LocalContext.current

    val pickTrack = rememberLauncherForActivityResult(
        OpenTrackDocument()
    ) { uri -> uri?.let(viewModel::selectTrack) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Geoimport") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RecorderSection()

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pickTrack.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(viewModel.trackName ?: "Select GPS track…")
                }
                if (viewModel.trackUri != null) {
                    TextButton(onClick = viewModel::clearTrack) { Text("Clear") }
                }
            }

            OutlinedTextField(
                value = viewModel.startFilename,
                onValueChange = { viewModel.startFilename = it },
                label = { Text("Start after filename (optional)") },
                placeholder = { Text("DSCF1234.RAF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = viewModel.startTimestamp,
                onValueChange = { viewModel.startTimestamp = it },
                label = { Text("Start after timestamp (optional)") },
                placeholder = { Text("2026-07-17 14:30") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Leave both empty to continue after the newest file in the last \"Import NN\" folder.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = viewModel.toleranceMinutes,
                onValueChange = { viewModel.toleranceMinutes = it },
                label = { Text("Geotag tolerance (minutes)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (!Environment.isExternalStorageManager()) {
                        viewModel.logLines.add(
                            "Grant \"All files access\" in the settings screen that just opened, then press Import again."
                        )
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (viewModel.running) "Importing…" else "Import from SD card")
            }

            HorizontalDivider()

            val listState = rememberLazyListState()
            LaunchedEffect(viewModel.logLines.size) {
                if (viewModel.logLines.isNotEmpty()) {
                    listState.animateScrollToItem(viewModel.logLines.size - 1)
                }
            }
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(state = listState) {
                    items(viewModel.logLines) { line ->
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

@Composable
fun RecorderSection() {
    val context = LocalContext.current
    val recording by TrackRecorderService.state.collectAsState()
    var intervalMinutes by rememberSaveable { mutableStateOf("1") }
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

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = intervalMinutes,
            onValueChange = { intervalMinutes = it },
            label = { Text("Interval (min)") },
            singleLine = true,
            enabled = recording == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(0.4f),
        )
        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it },
            label = { Text("Track filename") },
            placeholder = { Text(defaultName) },
            supportingText = { Text("Leave empty to use the default name") },
            singleLine = true,
            enabled = recording == null,
            modifier = Modifier.weight(0.6f),
        )
    }

    Button(
        onClick = {
            if (recording != null) {
                context.startService(
                    Intent(context, TrackRecorderService::class.java)
                        .setAction(TrackRecorderService.ACTION_STOP)
                )
            } else if (!Environment.isExternalStorageManager()) {
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
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (recording != null) "Stop recording" else "Record GPS track")
    }

    recording?.let { rec ->
        Text(
            "Recording ${rec.fileName} — ${rec.pointCount} points",
            style = MaterialTheme.typography.bodySmall,
        )
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
