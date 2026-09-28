package com.pbungert.geoimport.core.imports

import java.io.File
import java.io.StringReader
import java.time.Instant
import java.util.Properties

/**
 * Import settings that describe the camera and the photos rather than the
 * device: which file types to leave on the card, how far the camera clock is
 * off, which zone it was set to, and how far from a track a photo may still be
 * placed. Synced between devices, so a clock offset found on the tablet also
 * corrects the next import on the PC.
 *
 * Each setting carries when it was last changed, and two devices that both
 * changed something keep the later change of each setting - not the later
 * file, which would undo a change made to another setting on the other side.
 *
 * Values are kept as the text they were entered as; reading them is up to the
 * caller, as it was when they lived in each device's own preferences.
 */
class CameraSettings(private val entries: Map<String, Entry> = emptyMap()) {

    data class Entry(val value: String, val changedAt: Instant)

    operator fun get(key: String): String? = entries[key]?.value

    fun changedAt(key: String): Instant? = entries[key]?.changedAt

    /** A copy with [key] set to [value]. Setting the value it already has changes nothing. */
    fun with(key: String, value: String, at: Instant): CameraSettings =
        if (entries[key]?.value == value) this else CameraSettings(entries + (key to Entry(value, at)))

    /** File types to leave on the card, lower case and without the dot. */
    val excludedTypes: Set<String>
        get() = parseTypes(get(EXCLUDED_TYPES).orEmpty())

    fun withExcludedTypes(types: Set<String>, at: Instant) =
        with(EXCLUDED_TYPES, types.map { it.lowercase() }.sorted().joinToString(","), at)

    /** Sorted keys and no date comment, so the same settings are the same bytes. */
    fun toBytes(): ByteArray = buildString {
        for ((key, entry) in entries.toSortedMap()) {
            append("$key=${escape(entry.value)}\n")
            append("$key.changedAt=${entry.changedAt}\n")
        }
    }.toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?) = other is CameraSettings && other.entries == entries
    override fun hashCode() = entries.hashCode()
    override fun toString() = "CameraSettings($entries)"

    companion object {
        const val FILE_NAME = "import-settings.properties"

        const val EXCLUDED_TYPES = "excludedTypes"
        const val CLOCK_OFFSET_MINUTES = "clockOffsetMinutes"
        const val PHOTO_TIME_ZONE = "photoTimeZone"
        const val TOLERANCE_MINUTES = "toleranceMinutes"

        /** The settings file that belongs with [tracksDir], beside [LastImport]'s. */
        fun fileIn(tracksDir: File) = File(File(tracksDir, "State"), FILE_NAME)

        /** Empty settings when [file] is missing or unreadable. */
        fun read(file: File): CameraSettings =
            if (file.isFile) runCatching { parse(file.readBytes()) }.getOrDefault(CameraSettings())
            else CameraSettings()

        /** A setting without a readable time is skipped rather than guessed at. */
        fun parse(bytes: ByteArray): CameraSettings {
            val p = Properties().apply { load(StringReader(bytes.toString(Charsets.UTF_8))) }
            val entries = p.stringPropertyNames()
                .filterNot { it.endsWith(".changedAt") }
                .mapNotNull { key ->
                    val at = p.getProperty("$key.changedAt")
                        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        ?: return@mapNotNull null
                    key to Entry(p.getProperty(key), at)
                }
                .toMap()
            return CameraSettings(entries)
        }

        /** Every setting either side has, each at its later change. */
        fun merge(a: CameraSettings, b: CameraSettings): CameraSettings {
            val keys = a.entries.keys + b.entries.keys
            return CameraSettings(
                keys.associateWith { key ->
                    listOfNotNull(a.entries[key], b.entries[key]).maxBy { it.changedAt }
                }
            )
        }

        /** [merge] for sync, on file content. */
        fun mergeBytes(local: ByteArray, remote: ByteArray): ByteArray {
            val l = runCatching { parse(local) }.getOrDefault(CameraSettings())
            val r = runCatching { parse(remote) }.getOrDefault(CameraSettings())
            return merge(l, r).toBytes()
        }

        /** Writes [settings] to [file], keeping any later change the file already holds. */
        fun save(settings: CameraSettings, file: File) {
            val merged = merge(read(file), settings)
            file.parentFile?.mkdirs()
            file.writeBytes(merged.toBytes())
        }

        /** "jpg, .MOV" to {jpg, mov}. */
        fun parseTypes(text: String): Set<String> = text.split(',')
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", " ")
    }
}
