package com.pbungert.geoimport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pbungert.geoimport.recorder.TrackRecorderService
import com.pbungert.geoimport.ui.theme.GeoimportTheme
import kotlinx.coroutines.launch

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
