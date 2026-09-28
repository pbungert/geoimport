package com.pbungert.geoimport.core.imports

import java.io.File
import java.io.StringReader
import java.time.Instant
import java.util.Properties

/**
 * Where the last import stopped, on whichever device ran it. Synced between
 * devices, so an import on the tablet continues after what the PC already
 * copied off the card, and the next `Import NN` folder is numbered above both.
 *
 * It lives in `State/` inside the tracks folder, which is the folder that
 * syncs: `Documents/Geoimport` on Android, the Drive for Desktop mirror on a PC.
 */
data class LastImport(
    /** The last photo copied, the resume point for the next import. */
    val lastFile: String,
    /** The number of the `Import NN` folder it went into. */
    val importNumber: Int,
    val importedAt: Instant,
    /** Which device ran the import, to say so in the log. */
    val device: String,
) {
    /** A fixed layout without a date comment, so the same state is the same bytes. */
    fun toBytes(): ByteArray = buildString {
        append("lastFile=${escape(lastFile)}\n")
        append("importNumber=$importNumber\n")
        append("importedAt=$importedAt\n")
        append("device=${escape(device)}\n")
    }.toByteArray(Charsets.UTF_8)

    companion object {
        const val FILE_NAME = "last-import.properties"

        /** The state file that belongs with [tracksDir]. */
        fun fileIn(tracksDir: File) = File(File(tracksDir, "State"), FILE_NAME)

        /** Null when [file] is missing or does not hold a whole state. */
        fun read(file: File): LastImport? =
            if (file.isFile) runCatching { parse(file.readBytes()) }.getOrNull() else null

        fun parse(bytes: ByteArray): LastImport? {
            val p = Properties().apply { load(StringReader(bytes.toString(Charsets.UTF_8))) }
            return LastImport(
                lastFile = p.getProperty("lastFile")?.ifEmpty { null } ?: return null,
                importNumber = p.getProperty("importNumber")?.toIntOrNull() ?: return null,
                importedAt = p.getProperty("importedAt")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null,
                device = p.getProperty("device").orEmpty(),
            )
        }

        /**
         * The state both devices agree on: the later resume point and the
         * higher folder number. Which file came later is judged the way the
         * importer orders a card, so a counter that wrapped from 9999 to 0001
         * still counts as moving forward.
         */
        fun merge(a: LastImport?, b: LastImport?): LastImport? {
            if (a == null || b == null) return a ?: b
            val later = when {
                a.lastFile.equals(b.lastFile, ignoreCase = true) -> maxOf(a, b, compareBy { it.importedAt })
                isLater(b.lastFile, than = a.lastFile) -> b
                else -> a
            }
            return later.copy(importNumber = maxOf(a.importNumber, b.importNumber))
        }

        /** [merge] for sync, on file content. Unreadable content gives way to the other side. */
        fun mergeBytes(local: ByteArray, remote: ByteArray): ByteArray {
            val merged = merge(runCatching { parse(local) }.getOrNull(), runCatching { parse(remote) }.getOrNull())
            return merged?.toBytes() ?: remote
        }

        /** Stores [state] in [file], keeping whatever of the old state was further along. */
        fun record(state: LastImport, file: File) {
            val merged = merge(read(file), state) ?: state
            file.parentFile?.mkdirs()
            file.writeBytes(merged.toBytes())
        }

        /** Whether [name] comes after [than] in the camera's numbering. */
        fun isLater(name: String, than: String): Boolean {
            val order = PhotoImporter.sortByFilenameChronological(listOf(File(than), File(name)))
            // Neither is numbered: nothing says which came first, so no change.
            return order.size == 2 && order.last().name == name
        }

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", " ")
    }
}
