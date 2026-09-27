package com.pbungert.geoimport.sync

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.pbungert.geoimport.core.sync.ConflictPolicy
import com.pbungert.geoimport.core.sync.DirectoryStore
import com.pbungert.geoimport.core.sync.SyncEngine
import com.pbungert.geoimport.core.sync.SyncIndex
import com.pbungert.geoimport.core.track.TrackParser
import com.pbungert.geoimport.recorder.TrackRecorderService
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One sync of the track folder with Drive. A plain [Worker], because every
 * step blocks on the network anyway.
 */
class GeoimportSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    /**
     * One sync at a time. The hourly run and a "sync now" can start in the
     * same instant, as they do right after signing in, and two runs on the
     * same index would each upload the same new tracks, or each create the
     * Drive folder.
     */
    override fun doWork(): Result = synchronized(LOCK) { syncOnce() }

    private fun syncOnce(): Result {
        val context = applicationContext
        SyncManager.init(context)
        val account = SyncManager.account(context) ?: return Result.success()
        // Without it the folder reads as empty, and every synced track would
        // be taken for one the user deleted.
        if (!Environment.isExternalStorageManager()) {
            SyncManager.finished(context, "Needs access to all files", changedLocally = false)
            return Result.success()
        }

        SyncManager.started()
        val token = try {
            val result = Tasks.await(SyncManager.authorize(context, account), 30, TimeUnit.SECONDS)
            if (result.hasResolution()) {
                SyncManager.lostAccess(context)
                return Result.success()
            }
            result.accessToken ?: throw IOException("No access token")
        } catch (e: Exception) {
            Log.w(TAG, "Could not get a Drive token", e)
            SyncManager.finished(context, "Sign-in failed: ${e.message ?: e.javaClass.simpleName}", false)
            return Result.retry()
        }

        return try {
            val report = syncTracks(context, token)
            Log.i(
                TAG,
                "Synced: ${report.uploaded.size} up, ${report.downloaded.size} down, " +
                    "${report.renamed.size} moved aside, ${report.failures.size} failed",
            )
            report.failures.forEach { Log.w(TAG, it) }
            val error = report.failures.firstOrNull()?.let { first ->
                if (report.failures.size == 1) first else "$first (and ${report.failures.size - 1} more)"
            }
            SyncManager.finished(context, error, report.changedLocally)
            Result.success()
        } catch (e: DriveAuthException) {
            Log.w(TAG, "Drive rejected the token", e)
            SyncManager.lostAccess(context)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            SyncManager.finished(context, e.message ?: e.javaClass.simpleName, changedLocally = false)
            Result.retry()
        }
    }

    private fun syncTracks(context: Context, token: String): SyncEngine.Report {
        TrackRecorderService.migrateLegacyTracksDir(context)
        val dir = TrackRecorderService.tracksDir()
        val isTrack = { name: String -> name.substringAfterLast('.', "").lowercase() in TrackParser.EXTENSIONS }
        val local = DirectoryStore(dir, isTrack) { file ->
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
        }
        val engine = SyncEngine(
            local,
            DriveRemoteStore(token, SyncManager.DRIVE_FOLDER),
            ConflictPolicy.KeepBoth,
            accepts = isTrack,
        )
        val indexFile = SyncManager.indexFile(context)
        // A folder that is gone altogether was cleared out, not pruned track by
        // track, so it fills up again like a new device's rather than
        // remembering every track as deleted.
        val index = if (dir.isDirectory) SyncIndex.read(indexFile) else SyncIndex()
        val skip = TrackRecorderService.activeFileName(context)?.let(::setOf).orEmpty()
        return engine.sync(index, skip) { it.write(indexFile) }
    }

    private companion object {
        const val TAG = "GeoimportSync"
        val LOCK = Any()
    }
}
