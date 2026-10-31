package com.mygallery.app.pro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Metadata for one media object included in a dedicated media archive. */
data class MediaBackupItem(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val size: Long
)

/** A fully staged and validated archive. Call discard() when the user dismisses its preview. */
data class MediaBackupInspection internal constructor(
    internal val archiveFile: File,
    val items: List<MediaBackupItem>
)

/** Dedicated media archives; this deliberately does not change the settings archive format/API. */
object BackupMedia {
    private const val FORMAT = "my-gallery-media-backup-v1"
    private const val MANIFEST = "manifest.txt"
    private const val MAX_ITEMS = 1_000
    private const val MAX_ITEM_BYTES = 1L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    fun createLocal(context: Context, sources: List<Uri>, destination: Uri): BackupOperationResult = safely("Could not create media archive") {
        if (sources.isEmpty()) throw IOException("Select at least one photo or video")
        val archive = createArchive(context, sources)
        try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("Could not open the selected archive destination")
            output.use { copyBounded(FileInputStream(archive), it, MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) }
        } finally { archive.delete() }
        BackupOperationResult(true, "Selected media saved to archive")
    }

    fun upload(context: Context, sources: List<Uri>, endpoint: String, username: String, password: String): BackupOperationResult = safely("WebDAV media upload failed") {
        if (sources.isEmpty()) throw IOException("Select at least one photo or video")
        val archive = createArchive(context, sources)
        try {
            val url = secureUrl(endpoint)
            val connection = openConnection(url, username, password, "PUT")
            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/zip")
                connection.setFixedLengthStreamingMode(archive.length())
                connection.outputStream.use { output -> FileInputStream(archive).use { copyBounded(it, output, MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) } }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("WebDAV upload failed (HTTP $code)${errorText(connection)}")
            } finally { connection.disconnect() }
        } finally { archive.delete() }
        BackupOperationResult(true, "Selected media uploaded")
    }

    fun inspectLocal(context: Context, source: Uri): Pair<BackupOperationResult, MediaBackupInspection?> = try {
        val staged = File.createTempFile("gallery-media-restore-", ".zip", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Could not open the selected archive")
            input.use { FileOutputStream(staged).use { out -> copyBounded(it, out, MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) } }
            BackupOperationResult(true, "Media archive validated") to inspectStaged(staged)
        } catch (error: Exception) { staged.delete(); throw error }
    } catch (error: Exception) {
        BackupOperationResult(false, error.message ?: "Could not inspect media archive") to null
    }

    fun inspectRemote(context: Context, endpoint: String, username: String, password: String): Pair<BackupOperationResult, MediaBackupInspection?> = try {
        val url = secureUrl(endpoint)
        val staged = File.createTempFile("gallery-media-remote-", ".zip", context.cacheDir)
        try {
            val connection = openConnection(url, username, password, "GET")
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("WebDAV download failed (HTTP $code)${errorText(connection)}")
                val input = connection.inputStream
                input.use { FileOutputStream(staged).use { out -> copyBounded(it, out, MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) } }
            } finally { connection.disconnect() }
            BackupOperationResult(true, "Remote media archive validated") to inspectStaged(staged)
        } catch (error: Exception) { staged.delete(); throw error }
    } catch (error: Exception) {
        BackupOperationResult(false, error.message ?: "Could not inspect remote media archive") to null
    }

    /** Validates the complete ZIP and its CRCs before creating any MediaStore rows. */
    fun restore(context: Context, inspection: MediaBackupInspection, selectedIds: Set<String>): BackupOperationResult = safely("Could not restore selected media") {
        val chosen = inspection.items.filter { it.id in selectedIds }
        if (chosen.isEmpty()) throw IOException("Select at least one archive item to restore")
        var restored = 0
        try {
            restoreValidated(context, inspection.archiveFile, chosen) { restored = it }
            BackupOperationResult(true, "$restored media item${if (restored == 1) "" else "s"} restored; originals were left untouched")
        } catch (error: Exception) {
            if (restored > 0) BackupOperationResult(false, "$restored item(s) restored before failure: ${error.message ?: "restore failed"}")
            else throw error
        }
    }

    fun discard(inspection: MediaBackupInspection) { inspection.archiveFile.delete() }

    private fun inspectStaged(file: File): MediaBackupInspection {
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) {
            throw IOException("Archive is empty or exceeds the media archive limit")
        }
        var manifest: List<MediaBackupItem>? = null
        val observed = linkedSetOf<String>()
        val observedSizes = mutableMapOf<String, Long>()
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(file), BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !safeZipName(name) || !observed.add(name)) throw IOException("Archive contains an unsafe or duplicate ZIP entry")
                if (name == MANIFEST) {
                    if (manifest != null) throw IOException("Archive has duplicate metadata")
                    val bytes = readBounded(zip, MAX_MANIFEST_BYTES.toLong())
                    manifest = parseManifest(bytes)
                } else {
                    if (!name.matches(Regex("media/[0-9]{6}\\.bin"))) throw IOException("Archive contains an unsupported ZIP entry")
                    var entrySize = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entrySize += count
                        total += count
                        if (entrySize > MAX_ITEM_BYTES || total > MAX_TOTAL_BYTES) throw IOException("Archive expands beyond the media size limit")
                    }
                    observedSizes[name] = entrySize
                }
                zip.closeEntry()
            }
        }
        val items = manifest ?: throw IOException("Media archive has no manifest")
        val expected = setOf(MANIFEST) + items.map { it.id }
        if (observed != expected) throw IOException("Media archive is incomplete or contains extra entries")
        for (item in items) {
            if (item.size > MAX_ITEM_BYTES || observedSizes[item.id] != item.size) {
                throw IOException("A media item size does not match the validated payload")
            }
        }
        return MediaBackupInspection(file, items)
    }

    private fun restoreValidated(context: Context, archive: File, items: List<MediaBackupItem>, onRestored: (Int) -> Unit) {
        val byId = items.associateBy { it.id }
        var restored = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive), BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val item = byId[entry.name]
                if (item != null) {
                    restoreOne(context, zip, item)
                    restored++
                    onRestored(restored)
                }
                zip.closeEntry()
            }
        }
    }

    private fun restoreOne(context: Context, input: InputStream, item: MediaBackupItem) {
        val resolver = context.contentResolver
        val isImage = item.mimeType.startsWith("image/")
        val collection = if (isImage) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val directory = if (isImage) Environment.DIRECTORY_PICTURES + "/MyGallery/Restored" else Environment.DIRECTORY_MOVIES + "/MyGallery/Restored"
        val safeName = safeDisplayName(item.displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "restored_${System.currentTimeMillis()}_$safeName")
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values) ?: throw IOException("Could not create a pending MediaStore item")
        try {
            val out = resolver.openOutputStream(target, "w") ?: throw IOException("Could not open pending media output")
            var written = 0L
            out.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > item.size || written > MAX_ITEM_BYTES) throw IOException("Media payload size does not match its manifest")
                    output.write(buffer, 0, count)
                }
            }
            if (written != item.size) throw IOException("Media payload size does not match its manifest")
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(target, publish, null, null) != 1) throw IOException("Could not publish restored media")
        } catch (error: Exception) {
            try { resolver.delete(target, null, null) } catch (_: Exception) { }
            throw error
        }
    }

    private fun createArchive(context: Context, sources: List<Uri>): File {
        if (sources.size > MAX_ITEMS) throw IOException("Select no more than $MAX_ITEMS media items")
        val items = sources.mapIndexed { index, uri ->
            val mime = context.contentResolver.getType(uri)?.lowercase()?.substringBefore(';')
                ?: throw IOException("Could not determine a selected file's media type")
            if (!(mime.startsWith("image/") || mime.startsWith("video/")) || mime == "image/svg+xml") throw IOException("Only photo and video files can be archived")
            val name = queryName(context, uri)
            val size = querySize(context, uri)
            if (size != null && (size < 0 || size > MAX_ITEM_BYTES)) throw IOException("A selected media item exceeds the size limit")
            MediaBackupItem("media/${(index + 1).toString().padStart(6, '0')}.bin", safeDisplayName(name), mime, size ?: -1L)
        }
        if (items.map { it.id }.distinct().size != items.size) throw IOException("Duplicate selected media item")
        val temp = File.createTempFile("gallery-media-backup-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), BUFFER_SIZE)).use { zip ->
                var total = 0L
                val measuredItems = mutableListOf<MediaBackupItem>()
                for ((index, uri) in sources.withIndex()) {
                    val item = items[index]
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read a selected media item")
                    zip.putNextEntry(ZipEntry(item.id))
                    var actual = 0L
                    input.use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            actual += count; total += count
                            if (actual > MAX_ITEM_BYTES || total > MAX_TOTAL_BYTES) throw IOException("Selected media exceeds the archive size limit")
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    if (item.size >= 0 && item.size != actual) throw IOException("A selected media item changed while being archived")
                    measuredItems += item.copy(size = actual)
                }
                val manifest = encodeManifest(measuredItems)
                zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest); zip.closeEntry()
            }
            if (temp.length() > MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES) throw IOException("Media archive exceeds the size limit")
            return temp
        } catch (error: Exception) { temp.delete(); throw error }
    }

    private fun encodeManifest(items: List<MediaBackupItem>): ByteArray {
        val text = buildString {
            append(FORMAT).append('\n')
            append(items.size).append('\n')
            for (item in items) {
                val actualSize = item.size
                append(item.id).append('\t')
                    .append(Base64.encodeToString(item.displayName.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)).append('\t')
                    .append(Base64.encodeToString(item.mimeType.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)).append('\t')
                    .append(actualSize).append('\n')
            }
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseManifest(bytes: ByteArray): List<MediaBackupItem> {
        val text = String(bytes, StandardCharsets.UTF_8)
        val lines = text.split('\n')
        if (lines.size < 3 || lines[0] != FORMAT || !lines.last().isEmpty()) throw IOException("Media archive manifest is invalid")
        val count = lines[1].toIntOrNull() ?: throw IOException("Media archive item count is invalid")
        if (count !in 1..MAX_ITEMS || lines.size != count + 3) throw IOException("Media archive item count is invalid")
        val ids = mutableSetOf<String>()
        return lines.subList(2, 2 + count).map { line ->
            val parts = line.split('\t')
            if (parts.size != 4 || !parts[0].matches(Regex("media/[0-9]{6}\\.bin")) || !ids.add(parts[0])) throw IOException("Media archive manifest has an invalid entry")
            val name = decodeBase64Text(parts[1])
            val mime = decodeBase64Text(parts[2]).lowercase()
            val size = parts[3].toLongOrNull() ?: throw IOException("Media archive item size is invalid")
            if (size < 0 || size > MAX_ITEM_BYTES || !(mime.startsWith("image/") || mime.startsWith("video/")) || mime == "image/svg+xml") {
                throw IOException("Media archive metadata is unsupported")
            }
            safeDisplayName(name)
            MediaBackupItem(parts[0], name, mime, size)
        }
    }

    private fun decodeBase64Text(value: String): String = try {
        String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8).also {
            if (it.indexOf('\u0000') >= 0 || it.length > 512) throw IOException("Media archive metadata is invalid")
        }
    } catch (error: Exception) { throw IOException("Media archive metadata is invalid", error) }

    private fun safeZipName(name: String): Boolean = name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') &&
        name.split('/').none { it.isEmpty() || it == "." || it == ".." } && !name.any { it == '\u0000' || it.isISOControl() }

    private fun safeDisplayName(name: String): String {
        val normalized = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (normalized.isBlank() || normalized == "." || normalized == ".." || normalized.length > 255 || normalized.any { it == '\u0000' || it.isISOControl() }) {
            throw IOException("A media filename is invalid")
        }
        return normalized
    }

    private fun queryName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "media"
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return null
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyBounded(input, output, limit)
        return output.toByteArray()
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > limit) throw IOException("Media archive exceeds the transfer size limit")
            output.write(buffer, 0, count)
        }
    }

    private fun secureUrl(endpoint: String): URL {
        val url = try { URL(endpoint.trim()) } catch (_: Exception) { throw IOException("Enter a valid WebDAV URL") }
        if (url.protocol != "https" || url.host.isNullOrBlank() || url.userInfo != null || url.ref != null) {
            throw IOException("WebDAV URL must use HTTPS and contain no embedded credentials or fragment")
        }
        return url
    }

    private fun openConnection(url: URL, username: String, password: String, method: String): HttpURLConnection {
        if (username.contains(':')) throw IOException("Username cannot contain a colon")
        val connection = url.openConnection() as? HttpURLConnection ?: throw IOException("Unsupported WebDAV connection")
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/zip, application/octet-stream")
        if (username.isNotEmpty() || password.isNotEmpty()) {
            val auth = "$username:$password".toByteArray(StandardCharsets.UTF_8)
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth, Base64.NO_WRAP))
        }
        return connection
    }

    private fun errorText(connection: HttpURLConnection): String {
        return try {
            connection.errorStream?.use { stream ->
                String(readBounded(stream, 2_048), StandardCharsets.UTF_8)
                    .replace(Regex("[\\r\\n\\t]+"), " ").take(200).let { if (it.isBlank()) "" else ": $it" }
            } ?: ""
        } catch (_: Exception) { "" }
    }

    private inline fun safely(fallback: String, operation: () -> BackupOperationResult): BackupOperationResult = try {
        operation()
    } catch (error: Exception) {
        BackupOperationResult(false, error.message ?: fallback)
    }
}
