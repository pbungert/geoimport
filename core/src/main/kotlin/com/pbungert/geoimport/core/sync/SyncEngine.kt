package com.pbungert.geoimport.core.sync

/**
 * Brings one local folder and one remote folder into step.
 *
 * [accepts] decides which remote files take part, the same way the local
 * store decides for its own: a stray photo dropped into the Drive folder is
 * not a track and does not come down. [merge] combines two versions of a file
 * and is required under [ConflictPolicy.Merge].
 */
class SyncEngine(
    private val local: LocalStore,
    private val remote: RemoteStore,
    private val policy: ConflictPolicy,
    private val accepts: (String) -> Boolean = { true },
    private val merge: ((local: ByteArray, remote: ByteArray) -> ByteArray)? = null,
) {
    init {
        require(policy != ConflictPolicy.Merge || merge != null) { "Merge policy needs a merge function" }
    }

    data class Report(
        val uploaded: List<String> = emptyList(),
        val downloaded: List<String> = emptyList(),
        /** Local files moved aside, as old name to new. */
        val renamed: List<Pair<String, String>> = emptyList(),
        /** What went wrong, one line per failed step. */
        val failures: List<String> = emptyList(),
    ) {
        /** Whether anything in the local folder changed. */
        val changedLocally get() = downloaded.isNotEmpty() || renamed.isNotEmpty()
    }

    /**
     * Runs one sync, changing [index] as it goes and handing it to [save]
     * after every step that got through. A step that fails is reported and the
     * rest still run; whatever it left undone comes up again next time.
     */
    fun sync(index: SyncIndex, skip: Set<String> = emptySet(), save: (SyncIndex) -> Unit = {}): Report {
        val remoteFiles = remote.list().filter { accepts(it.name) }
        val steps = SyncPlanner.plan(local.list(), remoteFiles, index, policy, skip)

        val uploaded = mutableListOf<String>()
        val downloaded = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()
        for (step in steps) {
            try {
                for (action in step) {
                    run(action, index, uploaded, downloaded, renamed)
                }
            } catch (e: Exception) {
                failures += "${describe(step.first())}: ${e.message ?: e.javaClass.simpleName}"
            }
            save(index)
        }
        return Report(uploaded, downloaded, renamed, failures)
    }

    private fun run(
        action: SyncAction,
        index: SyncIndex,
        uploaded: MutableList<String>,
        downloaded: MutableList<String>,
        renamed: MutableList<Pair<String, String>>,
    ) {
        when (action) {
            is SyncAction.Upload -> {
                val bytes = local.read(action.name)
                val file = remote.create(action.name, bytes)
                index.record(action.name, file.id, md5Hex(bytes))
                uploaded += action.name
            }
            is SyncAction.Update -> {
                val bytes = local.read(action.name)
                remote.update(action.remoteId, bytes)
                index.record(action.name, action.remoteId, md5Hex(bytes))
                uploaded += action.name
            }
            is SyncAction.Download -> {
                val bytes = remote.download(action.remoteId)
                local.write(action.name, bytes)
                index.record(action.name, action.remoteId, md5Hex(bytes))
                downloaded += action.name
            }
            is SyncAction.Merge -> {
                val merged = merge!!(local.read(action.name), remote.download(action.remoteId))
                local.write(action.name, merged)
                remote.update(action.remoteId, merged)
                index.record(action.name, action.remoteId, md5Hex(merged))
                downloaded += action.name
                uploaded += action.name
            }
            is SyncAction.Record -> index.record(action.name, action.remoteId, action.md5)
            is SyncAction.RenameLocal -> {
                if (!local.rename(action.from, action.to)) {
                    throw IllegalStateException("could not rename ${action.from} to ${action.to}")
                }
                index.synced.remove(action.from)?.let { index.synced[action.to] = it }
                renamed += action.from to action.to
            }
            is SyncAction.RenameRemote -> remote.rename(action.remoteId, action.to)
            is SyncAction.Forget -> {
                index.synced.remove(action.name)
                index.deleted += action.name
            }
            is SyncAction.Prune -> {
                index.synced.remove(action.name)
                index.deleted -= action.name
            }
        }
    }

    private fun SyncIndex.record(name: String, remoteId: String, md5: String) {
        synced[name] = SyncIndex.Entry(remoteId, md5)
        deleted -= name
    }

    private fun describe(action: SyncAction) = when (action) {
        is SyncAction.Upload -> "Upload ${action.name}"
        is SyncAction.Update -> "Update ${action.name}"
        is SyncAction.Download -> "Download ${action.name}"
        is SyncAction.Merge -> "Merge ${action.name}"
        is SyncAction.Record -> "Record ${action.name}"
        is SyncAction.RenameLocal -> "Rename ${action.from}"
        is SyncAction.RenameRemote -> "Rename remote ${action.to}"
        is SyncAction.Forget -> "Forget ${action.name}"
        is SyncAction.Prune -> "Prune ${action.name}"
    }
}
