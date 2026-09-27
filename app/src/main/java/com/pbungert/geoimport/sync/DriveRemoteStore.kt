package com.pbungert.geoimport.sync

import com.pbungert.geoimport.core.sync.RemoteFile
import com.pbungert.geoimport.core.sync.RemoteStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Drive said the token is no good: expired, revoked, or never valid. */
class DriveAuthException(message: String) : IOException(message)

/**
 * One folder in the user's Drive, over the plain REST API.
 *
 * With the drive.file scope the app sees only what it created itself, so the
 * folder is found by name among the app's own folders. Two devices signing in
 * at the same moment can each create one; the oldest wins, and the other's
 * files go back up into it on that device's next sync.
 *
 * [parentId] is null for a folder at the top of My Drive.
 */
class DriveRemoteStore(
    private val token: String,
    private val folderName: String,
    private val parentId: String? = null,
) : RemoteStore {

    val folderId: String by lazy { findFolder() ?: createFolder() }

    override fun list(): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val url = "$API/files?" + query(
                "q" to "'$folderId' in parents and trashed = false and mimeType != '$FOLDER_MIME'",
                "fields" to "nextPageToken,files(id,name,md5Checksum)",
                "orderBy" to "createdTime",
                "pageSize" to "1000",
                "pageToken" to page,
            )
            val json = JSONObject(request("GET", url).decodeToString())
            json.optJSONArray("files")?.forEachObject { files += it.toRemoteFile() }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return files
    }

    override fun download(id: String): ByteArray = request("GET", "$API/files/$id?alt=media")

    override fun create(name: String, content: ByteArray): RemoteFile {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
        val boundary = "geoimport-" + UUID.randomUUID()
        val body = buildMultipart(boundary, metadata, content)
        val response = request(
            "POST",
            "$UPLOAD/files?uploadType=multipart&fields=$FILE_FIELDS",
            body,
            "multipart/related; boundary=$boundary",
        )
        return JSONObject(response.decodeToString()).toRemoteFile()
    }

    override fun update(id: String, content: ByteArray): RemoteFile {
        val response = request(
            "PATCH",
            "$UPLOAD/files/$id?uploadType=media&fields=$FILE_FIELDS",
            content,
            "application/octet-stream",
        )
        return JSONObject(response.decodeToString()).toRemoteFile()
    }

    override fun rename(id: String, name: String): RemoteFile {
        val response = request(
            "PATCH",
            "$API/files/$id?fields=$FILE_FIELDS",
            JSONObject().put("name", name).toString().toByteArray(),
            JSON_MIME,
        )
        return JSONObject(response.decodeToString()).toRemoteFile()
    }

    /** The signed-in account's address, to show who the tracks belong to. */
    fun accountEmail(): String? {
        val json = JSONObject(request("GET", "$API/about?fields=user(emailAddress)").decodeToString())
        return json.optJSONObject("user")?.optString("emailAddress")?.ifEmpty { null }
    }

    private fun findFolder(): String? {
        val url = "$API/files?" + query(
            "q" to "name = '${escape(folderName)}' and mimeType = '$FOLDER_MIME' and " +
                "'${parentId ?: "root"}' in parents and trashed = false",
            "fields" to "files(id)",
            "orderBy" to "createdTime",
        )
        val files = JSONObject(request("GET", url).decodeToString()).optJSONArray("files")
        return files?.takeIf { it.length() > 0 }?.getJSONObject(0)?.getString("id")
    }

    private fun createFolder(): String {
        val metadata = JSONObject()
            .put("name", folderName)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parentId ?: "root"))
        val response = request("POST", "$API/files?fields=id", metadata.toString().toByteArray(), JSON_MIME)
        return JSONObject(response.decodeToString()).getString("id")
    }

    private fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH; Google's APIs take the override.
            if (method == "PATCH") {
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                connection.requestMethod = method
            }
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw DriveAuthException("Drive rejected the sign-in")
            }
            if (code !in 200..299) {
                val detail = connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                throw IOException("Drive answered $code${describeError(detail)}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun describeError(body: String): String = runCatching {
        ": " + JSONObject(body).getJSONObject("error").getString("message")
    }.getOrDefault("")

    private fun buildMultipart(boundary: String, metadata: JSONObject, content: ByteArray): ByteArray {
        val head = "--$boundary\r\n" +
            "Content-Type: $JSON_MIME\r\n\r\n" +
            "$metadata\r\n" +
            "--$boundary\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        return head.toByteArray() + content + tail.toByteArray()
    }

    private fun JSONObject.toRemoteFile() =
        RemoteFile(getString("id"), getString("name"), optString("md5Checksum"))

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) action(getJSONObject(i))
    }

    private fun query(vararg params: Pair<String, String?>) = params
        .filter { it.second != null }
        .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    /** Drive's query language quotes with single quotes and escapes with backslashes. */
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val JSON_MIME = "application/json; charset=UTF-8"
        const val FILE_FIELDS = "id,name,md5Checksum"
        const val TIMEOUT_MILLIS = 30_000
    }
}
