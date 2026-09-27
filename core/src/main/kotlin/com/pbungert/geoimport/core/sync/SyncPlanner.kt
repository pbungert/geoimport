package com.pbungert.geoimport.core.sync

/** One thing a sync does. The planner decides; [SyncEngine] carries it out. */
sealed class SyncAction {
    data class Upload(val name: String) : SyncAction()
    data class Update(val name: String, val remoteId: String) : SyncAction()
    data class Download(val remoteId: String, val name: String) : SyncAction()

    /** Both sides changed; the merged content replaces both. */
    data class Merge(val name: String, val remoteId: String) : SyncAction()

    /** Both sides already agree, and only the index has to learn it. */
    data class Record(val name: String, val remoteId: String, val md5: String) : SyncAction()

    /** Moves a local file aside, so a remote file can have its name. */
    data class RenameLocal(val from: String, val to: String) : SyncAction()

    /** Gives a remote file a name of its own when another already holds it. */
    data class RenameRemote(val remoteId: String, val to: String) : SyncAction()

    /** The user deleted a synced file here. Remembered, never carried across. */
    data class Forget(val name: String) : SyncAction()

    /** Neither side has the file any more, so the index can let it go. */
    data class Prune(val name: String) : SyncAction()
}

/** What happens when both sides changed the same file since the last sync. */
enum class ConflictPolicy {
    /**
     * The remote version keeps the name and the local one moves aside to
     * `name (2)`, for files that mean something as a whole, like tracks.
     */
    KeepBoth,

    /** The two versions are combined, for state that has a sensible union. */
    Merge,
}

object SyncPlanner {

    /** Names that could not round-trip through [SyncIndex] are left alone. */
    fun isSyncable(name: String) =
        name.isNotEmpty() && name.none { it == '\t' || it == '\n' || it == '\r' }

    /**
     * Works out how to bring [local] and [remote] into step, given what the
     * last sync left in [index]. Files named in [skip] are not touched at all:
     * a track that is still being recorded changes by the minute, and is synced
     * once it is finished.
     *
     * Each step is a list of actions to run in order, and a failed step must
     * not stop the ones after it.
     */
    fun plan(
        local: Map<String, String>,
        remote: List<RemoteFile>,
        index: SyncIndex,
        policy: ConflictPolicy,
        skip: Set<String> = emptySet(),
    ): List<List<SyncAction>> {
        val steps = mutableListOf<List<SyncAction>>()
        val localNow = local.filterKeys(::isSyncable).toMutableMap()
        val synced = index.synced.toMutableMap()
        val taken = (localNow.keys + remote.map { it.name } + index.names).toMutableSet()

        fun freeName(name: String): String {
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val base = name.substring(0, dot)
            val ext = name.substring(dot)
            var n = 2
            var candidate: String
            do candidate = "$base (${n++})$ext" while (candidate in taken)
            taken += candidate
            return candidate
        }

        // One remote file per name. The oldest keeps it, and the others get a
        // name of their own and are then new files like any other.
        val byName = linkedMapOf<String, RemoteFile>()
        for (r in remote.filter { isSyncable(it.name) }) {
            if (r.name !in byName) {
                byName[r.name] = r
                continue
            }
            val to = freeName(r.name)
            val step = mutableListOf<SyncAction>(SyncAction.RenameRemote(r.id, to))
            byName[to] = r.copy(name = to)
            // This device's own upload lost the race for the name. The local
            // file follows it, instead of the same recording coming back down
            // as a copy.
            val mine = synced.entries.firstOrNull { it.value.remoteId == r.id }
            if (mine != null && localNow[mine.key] == mine.value.md5) {
                step += SyncAction.RenameLocal(mine.key, to)
                localNow[to] = localNow.remove(mine.key)!!
                synced[to] = synced.remove(mine.key)!!
            }
            steps += step
        }

        val names = (localNow.keys + byName.keys + synced.keys + index.deleted).toSortedSet()
        for (name in names) {
            if (name in skip) continue
            val l = localNow[name]
            val r = byName[name]
            // An entry for another remote file says nothing about this one.
            val last = synced[name]?.takeIf { r == null || it.remoteId == r.id }
            val step: List<SyncAction> = when {
                l != null && r != null -> when {
                    l == r.md5 ->
                        if (last?.md5 == l && name !in index.deleted) emptyList()
                        else listOf(SyncAction.Record(name, r.id, l))
                    // Deleted here, and since then something new was saved
                    // under the same name. The new file is kept apart from
                    // the deleted one, which stays deleted.
                    name in index.deleted -> {
                        val to = freeName(name)
                        listOf(SyncAction.RenameLocal(name, to), SyncAction.Upload(to))
                    }
                    last != null && l == last.md5 -> listOf(SyncAction.Download(r.id, name))
                    last != null && r.md5 == last.md5 -> listOf(SyncAction.Update(name, r.id))
                    policy == ConflictPolicy.Merge -> listOf(SyncAction.Merge(name, r.id))
                    else -> {
                        val to = freeName(name)
                        listOf(
                            SyncAction.RenameLocal(name, to),
                            SyncAction.Upload(to),
                            SyncAction.Download(r.id, name),
                        )
                    }
                }
                // New here, or its remote copy was deleted. Deletions are not
                // carried across in either direction, so it goes back up.
                l != null -> listOf(SyncAction.Upload(name))
                r != null -> when {
                    name in index.deleted -> emptyList()
                    last != null -> listOf(SyncAction.Forget(name))
                    else -> listOf(SyncAction.Download(r.id, name))
                }
                else -> listOf(SyncAction.Prune(name))
            }
            if (step.isNotEmpty()) steps += step
        }
        return steps
    }
}
