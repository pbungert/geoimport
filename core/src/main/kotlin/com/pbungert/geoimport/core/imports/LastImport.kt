package com.pbungert.geoimport.core.imports

import java.io.File
import java.io.StringReader
import java.time.Instant
import java.util.Properties

/**
 * Where the last import from one camera stopped, on whichever device ran it.
 * Synced between devices, so an import on the tablet continues after what the
 * PC already copied off the card, and the next `Import NN` folder is numbered
 * above both. [LastImports] holds one of these per camera.
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
    /** Which camera the card was from; null when its photos did not say. */
    val camera: Camera? = null,
) {
    companion object {
        const val FILE_NAME = "last-import.properties"

        /** The state file that belongs with [tracksDir]. */
        fun fileIn(tracksDir: File) = File(File(tracksDir, "State"), FILE_NAME)

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

        /** Whether [name] comes after [than] in the camera's numbering. */
        fun isLater(name: String, than: String): Boolean {
            val order = PhotoImporter.sortByFilenameChronological(listOf(File(than), File(name)))
            // Neither is numbered: nothing says which came first, so no change.
            return order.size == 2 && order.last().name == name
        }
    }
}

/**
 * Every camera's [LastImport], as one file. One file rather than one per
 * camera, because the app only sees files it created itself: a camera first
 * imported on the PC would otherwise never reach it.
 *
 * The camera nobody could identify keeps the unprefixed keys older versions
 * wrote, so their file reads as that entry. Each known camera's keys start
 * with its [Camera.key], and its make, model and serial are written out to
 * say which camera an entry belongs to:
 *
 * ```
 * FUJIFILM_X-T2_81M52794.make=FUJIFILM
 * FUJIFILM_X-T2_81M52794.model=X-T2
 * FUJIFILM_X-T2_81M52794.serial=81M52794
 * FUJIFILM_X-T2_81M52794.lastFile=DSCF2175.RAF
 * ...
 * ```
 */
class LastImports(entries: Collection<LastImport> = emptyList()) {

    private val byCamera: Map<String?, LastImport> = entries.associateBy { it.camera?.key }

    val entries: List<LastImport> get() = byCamera.values.toList()

    /** The highest `Import NN` any camera's import went into. */
    val highestImportNumber: Int get() = byCamera.values.maxOfOrNull { it.importNumber } ?: 0

    /**
     * Where the last import from [camera] stopped. A file from before cameras
     * were told apart has only the unknown camera's entry, which stands in for
     * every camera until one has its own - the importer still checks that the
     * file it names is on the card.
     */
    fun forCamera(camera: Camera?): LastImport? {
        if (camera == null) return byCamera[null]
        return byCamera[camera.key]
            ?: byCamera.values.firstOrNull { it.camera?.matches(camera) == true }
            ?: byCamera[null]?.takeIf { byCamera.keys.all { it == null } }
    }

    /** A copy with [state] merged into its camera's entry. */
    fun with(state: LastImport): LastImports {
        val key = state.camera?.key
        return LastImports((byCamera + (key to LastImport.merge(byCamera[key], state)!!)).values)
    }

    /** A fixed layout without a date comment, so the same state is the same bytes. */
    fun toBytes(): ByteArray = buildString {
        val (unknown, known) = byCamera.entries.partition { it.key == null }
        val blocks = unknown.map { it.value } + known.sortedBy { it.key }.map { it.value }
        for ((index, state) in blocks.withIndex()) {
            if (index > 0) append("\n")
            val prefix = state.camera?.let { "${it.key}." }.orEmpty()
            state.camera?.let { camera ->
                camera.make?.let { append("${prefix}make=${escape(it)}\n") }
                camera.model?.let { append("${prefix}model=${escape(it)}\n") }
                camera.serial?.let { append("${prefix}serial=${escape(it)}\n") }
            }
            append("${prefix}lastFile=${escape(state.lastFile)}\n")
            append("${prefix}importNumber=${state.importNumber}\n")
            append("${prefix}importedAt=${state.importedAt}\n")
            append("${prefix}device=${escape(state.device)}\n")
        }
    }.toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?) = other is LastImports && other.byCamera == byCamera
    override fun hashCode() = byCamera.hashCode()
    override fun toString() = "LastImports(${byCamera.values})"

    companion object {
        /** Empty when [file] is missing or unreadable. */
        fun read(file: File): LastImports =
            if (file.isFile) runCatching { parse(file.readBytes()) }.getOrDefault(LastImports())
            else LastImports()

        /** An entry missing a field is skipped rather than guessed at. */
        fun parse(bytes: ByteArray): LastImports {
            val p = Properties().apply { load(StringReader(bytes.toString(Charsets.UTF_8))) }
            val prefixes = p.stringPropertyNames()
                .map { if ('.' in it) it.substringBeforeLast('.') + "." else "" }
                .toSet()
            return LastImports(prefixes.mapNotNull { entry(p, it) })
        }

        private fun entry(p: Properties, prefix: String): LastImport? {
            fun field(name: String) = p.getProperty(prefix + name)
            val camera = if (prefix.isEmpty()) null else Camera.of(field("make"), field("model"), field("serial")) ?: return null
            return LastImport(
                lastFile = field("lastFile")?.ifEmpty { null } ?: return null,
                importNumber = field("importNumber")?.toIntOrNull() ?: return null,
                importedAt = field("importedAt")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null,
                device = field("device").orEmpty(),
                camera = camera,
            )
        }

        /** Every camera either side has, each merged as [LastImport.merge] does. */
        fun merge(a: LastImports, b: LastImports): LastImports =
            b.byCamera.values.fold(a) { merged, state -> merged.with(state) }

        /** [merge] for sync, on file content. Unreadable content gives way to the other side. */
        fun mergeBytes(local: ByteArray, remote: ByteArray): ByteArray {
            val l = runCatching { parse(local) }.getOrDefault(LastImports())
            val r = runCatching { parse(remote) }.getOrDefault(LastImports())
            val merged = merge(l, r)
            return if (merged.byCamera.isEmpty()) remote else merged.toBytes()
        }

        /** Stores [state] in [file], keeping whatever of the old state was further along. */
        fun record(state: LastImport, file: File) {
            val merged = read(file).with(state)
            file.parentFile?.mkdirs()
            file.writeBytes(merged.toBytes())
        }

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", " ")
    }
}
