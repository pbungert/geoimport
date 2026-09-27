package com.pbungert.geoimport.core.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the last sync left behind on this device, which is what tells an edit
 * from a new file and a deletion from a file that never arrived.
 *
 * [synced] maps a local name to the remote file it was last in step with and
 * the content both sides had then. [deleted] holds names the user removed
 * here: they are never downloaded again, and never deleted remotely either.
 */
class SyncIndex(
    val synced: MutableMap<String, Entry> = mutableMapOf(),
    val deleted: MutableSet<String> = mutableSetOf(),
) {
    data class Entry(val remoteId: String, val md5: String)

    /** Every name this index knows, including deleted ones. */
    val names: Set<String> get() = synced.keys + deleted

    /**
     * One record per line, fields split by tabs. [SyncPlanner.isSyncable]
     * keeps tabs and line breaks out of names, so they cannot collide.
     */
    fun write(file: File) {
        file.parentFile?.mkdirs()
        val text = buildString {
            synced.toSortedMap().forEach { (name, e) -> append("S\t$name\t${e.remoteId}\t${e.md5}\n") }
            deleted.sorted().forEach { append("D\t$it\n") }
        }
        val part = File(file.path + ".part")
        part.writeText(text)
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** An empty index when [file] is missing; unreadable lines are skipped. */
        fun read(file: File): SyncIndex {
            val index = SyncIndex()
            if (!file.isFile) return index
            file.readLines().forEach { line ->
                val f = line.split('\t')
                when {
                    f.size == 4 && f[0] == "S" -> index.synced[f[1]] = Entry(f[2], f[3])
                    f.size == 2 && f[0] == "D" -> index.deleted += f[1]
                }
            }
            return index
        }
    }
}
