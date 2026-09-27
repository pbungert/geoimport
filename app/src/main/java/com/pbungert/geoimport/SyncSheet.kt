package com.pbungert.geoimport

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.pbungert.geoimport.sync.DriveRemoteStore
import com.pbungert.geoimport.sync.SyncManager
import com.pbungert.geoimport.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Turning sync on and off, and what it last did. Signing in is the whole
 * setup: the tracks go to a Geoimport folder in that account's Drive, and
 * every device signed in to the same account shares them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by SyncManager.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }

    // The account comes from Drive rather than the consent screen, which does
    // not say which account was picked.
    fun finish(result: AuthorizationResult) {
        val token = result.accessToken
        if (token == null) {
            busy = false
            signInError = "Google did not grant access."
            return
        }
        scope.launch {
            val email = withContext(Dispatchers.IO) {
                runCatching { DriveRemoteStore(token, SyncManager.DRIVE_FOLDER).accountEmail() }
            }
            busy = false
            email.onSuccess { account ->
                if (account == null) signInError = "Drive did not say which account this is."
                else SyncManager.signedIn(context, account)
            }.onFailure { signInError = "Could not reach Drive: ${it.message}" }
        }
    }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // A setup problem - a key Google does not know, or an account that is
        // not a test user - also comes back as "cancelled", with the real
        // reason inside the result. Only an empty one was the user backing out.
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK && data == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        runCatching {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        }.onSuccess(::finish).onFailure {
            busy = false
            signInError = describeSignInFailure(it)
        }
    }

    fun signIn(account: String?) {
        busy = true
        signInError = null
        SyncManager.authorize(context, account)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    finish(result)
                }
            }
            .addOnFailureListener {
                busy = false
                signInError = describeSignInFailure(it)
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sync tracks", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Tracks recorded on one device show up on your others. They are kept in a " +
                        "Geoimport folder in your own Google Drive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val account = status.account
            if (account == null) {
                Button(
                    onClick = { signIn(null) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(if (busy) "Signing in…" else "Sign in with Google")
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(account, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        describe(status),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.lastError != null || status.needsSignIn) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (status.needsSignIn) {
                    Button(
                        onClick = { signIn(account) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("Sign in again")
                    }
                } else {
                    OutlinedButton(
                        onClick = { SyncManager.syncNow(context) },
                        enabled = !status.running,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("Sync now")
                    }
                }
                TextButton(
                    onClick = { SyncManager.signOut(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop syncing")
                }
            }

            signInError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun describe(status: SyncStatus): String = when {
    status.needsSignIn -> "Google no longer grants access. Sign in again to keep syncing."
    status.running -> "Syncing…"
    status.lastError != null -> "Last sync failed: ${status.lastError}"
    status.lastSyncMillis != null -> "Last synced " + DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(status.lastSyncMillis).atZone(ZoneId.systemDefault()))
    else -> "Waiting for the first sync."
}

/**
 * What went wrong at sign-in, in terms of what to fix. Google's messages are
 * mostly bare status names, so the codes that mean "the setup is incomplete"
 * are spelled out.
 */
private fun describeSignInFailure(error: Throwable): String {
    Log.w("GeoimportSync", "Sign-in failed", error)
    val code = (error as? ApiException)?.statusCode
        ?: return "Sign-in failed: ${error.message ?: error.javaClass.simpleName}"
    return when (code) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google does not recognise this build of the app (code 10). Check the package " +
                "name and SHA-1 of the Android client in Google Cloud, or wait a few minutes " +
                "if you only just created it."
        CommonStatusCodes.CANCELED ->
            "Sign-in was cancelled (code 16). If you did not cancel it, check that the " +
                "account is a test user in Google Cloud, under Audience."
        CommonStatusCodes.NETWORK_ERROR -> "No connection to Google (code 7)."
        else -> "Sign-in failed (code $code): ${error.message}"
    }
}
