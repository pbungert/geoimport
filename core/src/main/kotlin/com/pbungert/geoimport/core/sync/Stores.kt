package com.pbungert.geoimport.core.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A file in the remote folder, as the remote describes it. */
data class RemoteFile(val id: String, val name: String, val md5: String)

/**
 * One folder of whole files somewhere else. Files are small - a day of
 * one-minute fixes is under 100 KB - so content travels as bytes, not streams.
 */
interface RemoteStore {
    /**
     * Every file in the folder, oldest first. Two files can share a name when
     * two devices uploaded it at the same moment; the oldest keeps it.
     */
    fun list(): List<RemoteFile>
    fun download(id: String): ByteArray
    fun create(name: String, content: ByteArray): RemoteFile
    fun update(id: String, content: ByteArray): RemoteFile
    fun rename(id: String, name: String): RemoteFile
}

/** The local side of a sync: files by name, with their content hash. */
interface LocalStore {
    /** Name to MD5 of every file taking part. */
    fun list(): Map<String, String>
    fun read(name: String): ByteArray

    /** Replaces or creates [name] whole, so no reader ever sees half a file. */
    fun write(name: String, content: ByteArray)

    /** False when [to] is taken or the move failed; nothing is overwritten. */
    fun rename(from: String, to: String): Boolean
}

/**
 * A plain folder, holding the files [accepts] lets through. [onWritten] hears
 * about every file this store creates or moves, so a caller can tell the
 * platform's media index about it.
 */
class DirectoryStore(
    val dir: File,
    private val accepts: (String) -> Boolean,
    private val onWritten: (File) -> Unit = {},
) : LocalStore {

    override fun list(): Map<String, String> =
        dir.listFiles { f -> f.isFile && isCandidate(f.name) }
            .orEmpty()
            .associate { it.name to md5Hex(it.readBytes()) }

    override fun read(name: String): ByteArray = File(dir, name).readBytes()

    override fun write(name: String, content: ByteArray) {
        dir.mkdirs()
        val target = File(dir, name)
        // The dot and the suffix keep a half-written download out of list(),
        // and out of anything else reading the folder for tracks.
        val part = File(dir, ".$name.part")
        part.writeBytes(content)
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        onWritten(target)
    }

    override fun rename(from: String, to: String): Boolean {
        val target = File(dir, to)
        if (target.exists()) return false
        if (!File(dir, from).renameTo(target)) return false
        onWritten(target)
        return true
    }

    private fun isCandidate(name: String) = !name.startsWith(".") && accepts(name)
}

/** Lower-case hex, the form Drive reports a file's checksum in. */
fun md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
