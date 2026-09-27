package com.pbungert.geoimport.sync

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pbungert.geoimport.core.sync.SyncIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.TimeUnit

/** What the settings sheet shows about sync, and what tells the track list to reload. */
data class SyncStatus(
    /** The Google account the tracks go to; null while sync is off. */
    val account: String? = null,
    val running: Boolean = false,
    val lastSyncMillis: Long? = null,
    /** Why the last run failed, or null when it went through. */
    val lastError: String? = null,
    /** Access was withdrawn, and only signing in again brings sync back. */
    val needsSignIn: Boolean = false,
    /** Goes up whenever a sync changed the track folder. */
    val localChanges: Int = 0,
)

/**
 * Sync of the track folder with a `Geoimport` folder in the user's Google
 * Drive. Off until someone signs in, and each Google account has its own
 * tracks.
 */
object SyncManager {

    private const val PREFS = "sync"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_LAST_SYNC = "lastSync"
    private const val KEY_LAST_ERROR = "lastError"
    private const val KEY_NEEDS_SIGN_IN = "needsSignIn"

    private const val WORK_PERIODIC = "sync-periodic"
    private const val WORK_NOW = "sync-now"

    const val DRIVE_FOLDER = "Geoimport"
    private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private var loaded = false

    /** Reads the saved state once per process, before anything shows it. */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        _status.value = SyncStatus(
            account = p.getString(KEY_ACCOUNT, null),
            lastSyncMillis = p.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L },
            lastError = p.getString(KEY_LAST_ERROR, null),
            needsSignIn = p.getBoolean(KEY_NEEDS_SIGN_IN, false),
        )
    }

    fun account(context: Context): String? = prefs(context).getString(KEY_ACCOUNT, null)

    /**
     * Asks for access to the app's own Drive files. With [account] null the
     * user picks an account; the result either carries a token or needs its
     * pending intent launched first.
     */
    fun authorize(context: Context, account: String? = null): Task<AuthorizationResult> {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .apply { if (account != null) setAccount(Account(account, "com.google")) }
            .build()
        return Identity.getAuthorizationClient(context).authorize(request)
    }

    /** Called once access is granted and the account is known. */
    fun signedIn(context: Context, account: String) {
        val switched = account(context) != account
        prefs(context).edit()
            .putString(KEY_ACCOUNT, account)
            .putBoolean(KEY_NEEDS_SIGN_IN, false)
            .remove(KEY_LAST_ERROR)
            .apply()
        // Another account's Drive knows nothing of what this index says.
        if (switched) indexFile(context).delete()
        _status.update { it.copy(account = account, needsSignIn = false, lastError = null) }
        schedule(context)
        syncNow(context)
    }

    /** Stops syncing. The tracks on this device and in Drive stay where they are. */
    fun signOut(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
        prefs(context).edit().clear().apply()
        indexFile(context).delete()
        _status.value = SyncStatus(localChanges = _status.value.localChanges)
    }

    /** Hourly in the background, whenever there is a network. */
    fun schedule(context: Context) {
        if (account(context) == null) return
        val request = PeriodicWorkRequestBuilder<GeoimportSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * A sync as soon as the network allows: at app start, after a recording,
     * and on demand. A run already waiting covers a second request.
     */
    fun syncNow(context: Context) {
        if (account(context) == null) return
        val request = OneTimeWorkRequestBuilder<GeoimportSyncWorker>()
            .setConstraints(networkConstraint())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Names the tracks folder has had in sync, deleted ones included, so a new
     * recording does not take a name that would clash with one in Drive.
     */
    fun knownTrackNames(context: Context): Set<String> = SyncIndex.read(indexFile(context)).names

    internal fun indexFile(context: Context) = File(context.filesDir, "sync/tracks.index")

    internal fun started() = _status.update { it.copy(running = true) }

    internal fun finished(context: Context, error: String?, changedLocally: Boolean) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .apply { if (error == null) putLong(KEY_LAST_SYNC, now) }
            .putString(KEY_LAST_ERROR, error)
            .apply()
        _status.update {
            it.copy(
                running = false,
                lastSyncMillis = if (error == null) now else it.lastSyncMillis,
                lastError = error,
                localChanges = it.localChanges + if (changedLocally) 1 else 0,
            )
        }
    }

    internal fun lostAccess(context: Context) {
        prefs(context).edit().putBoolean(KEY_NEEDS_SIGN_IN, true).apply()
        _status.update { it.copy(running = false, needsSignIn = true) }
    }

    private fun networkConstraint() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
