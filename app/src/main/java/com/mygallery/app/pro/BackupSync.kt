package com.mygallery.app.pro

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Result object suitable for displaying operation failures to the user. */
data class BackupOperationResult(val success: Boolean, val message: String)

/**
 * App-settings backup. Archives are deliberately limited to this allowlist; restore never extracts
 * archive paths to disk and never writes files outside the corresponding app-owned preferences.
 */
object BackupSync {
    private const val FORMAT_VERSION = "my-gallery-settings-backup-v1"
    private const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 3 * 1024 * 1024
    private const val MAX_PREF_VALUES = 10_000
    private const val MAX_STRING_LENGTH = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private val allowedPreferences = listOf("settings", "ui", "custom_gallery_albums")

    /** Local SAF export retained as a simple Boolean API for existing callers. */
    fun backupSettings(context: Context, destination: Uri): Boolean =
        backupSettingsDetailed(context, destination).success

    fun backupSettingsDetailed(context: Context, destination: Uri): BackupOperationResult = try {
        val bytes = createBackup(context)
        val output = context.contentResolver.openOutputStream(destination, "w")
            ?: throw IOException("Could not open the selected backup destination")
        output.use { it.write(bytes) }
        BackupOperationResult(true, "Settings backup saved")
    } catch (error: Exception) {
        BackupOperationResult(false, error.message ?: "Could not create settings backup")
    }

    /** Validates the whole selected archive before applying any preference changes. */
    fun restoreSettings(context: Context, source: Uri): BackupOperationResult = try {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Could not open the selected backup")
        val bytes = input.use { readBounded(it, MAX_ARCHIVE_BYTES) }
        restoreBytes(context, bytes)
    } catch (error: Exception) {
        BackupOperationResult(false, error.message ?: "Could not restore settings backup")
    }

    /** Uploads a fresh settings archive only when explicitly invoked by the caller. */
    fun uploadSettings(context: Context, endpoint: String, username: String, password: String): BackupOperationResult = try {
        val bytes = createBackup(context)
        val response = webDavRequest(endpoint, username, password, "PUT", bytes)
        if (response.first in 200..299) BackupOperationResult(true, "Backup uploaded (HTTP ${response.first})")
        else BackupOperationResult(false, "WebDAV upload failed (HTTP ${response.first})${response.second}")
    } catch (error: Exception) {
        BackupOperationResult(false, error.message ?: "WebDAV upload failed")
    }

    /** Downloads, bounds-checks, validates, and restores a remote archive without writing it to disk. */
    fun downloadAndRestoreSettings(
        context: Context,
        endpoint: String,
        username: String,
        password: String
    ): BackupOperationResult = try {
        val response = webDavRequest(endpoint, username, password, "GET", null)
        if (response.first !in 200..299) {
            BackupOperationResult(false, "WebDAV download failed (HTTP ${response.first})${response.second}")
        } else {
            restoreBytes(context, response.third ?: throw IOException("The server returned an empty response"))
        }
    } catch (error: Exception) {
        BackupOperationResult(false, error.message ?: "WebDAV download failed")
    }

    private fun createBackup(context: Context): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        entries["manifest.txt"] = (FORMAT_VERSION + "\n" + allowedPreferences.joinToString("\n") + "\n")
            .toByteArray(StandardCharsets.UTF_8)
        for (name in allowedPreferences) {
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (preferences.size > MAX_PREF_VALUES) throw IOException("Too many settings to back up")
            entries["preferences/$name.xml"] = encodePreferences(preferences)
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                if (bytes.size > MAX_ENTRY_BYTES) throw IOException("Settings backup is too large")
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        if (output.size() > MAX_ARCHIVE_BYTES) throw IOException("Settings backup is too large")
        return output.toByteArray()
    }

    private fun restoreBytes(context: Context, archive: ByteArray): BackupOperationResult {
        if (archive.isEmpty() || archive.size > MAX_ARCHIVE_BYTES) throw IOException("Invalid or oversized backup archive")
        val expectedNames = setOf("manifest.txt") + allowedPreferences.map { "preferences/$it.xml" }
        val entries = linkedMapOf<String, ByteArray>()
        var totalUncompressed = 0
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                // Exact name matching rejects traversal, absolute paths, directory entries, and unknown files.
                if (entry.isDirectory || name !in expectedNames || entries.containsKey(name)) {
                    throw IOException("Backup contains an unsupported or duplicate entry")
                }
                val data = readBounded(zip, MAX_ENTRY_BYTES)
                totalUncompressed += data.size
                if (totalUncompressed > MAX_ARCHIVE_BYTES) throw IOException("Backup expands beyond the allowed size")
                entries[name] = data
                zip.closeEntry()
            }
        }
        if (entries.keys != expectedNames) throw IOException("Backup is incomplete or has an unsupported format")
        val manifest = String(entries.getValue("manifest.txt"), StandardCharsets.UTF_8)
        val expectedManifest = FORMAT_VERSION + "\n" + allowedPreferences.joinToString("\n") + "\n"
        if (manifest != expectedManifest) throw IOException("Backup manifest is invalid")

        // Parse and validate every preference entry before clearing or writing any current values.
        val parsed = linkedMapOf<String, Map<String, Any>>()
        for (name in allowedPreferences) {
            parsed[name] = decodePreferences(entries.getValue("preferences/$name.xml"))
        }
        for ((name, values) in parsed) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in values) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                    else -> throw IOException("Backup contains an unsupported setting value")
                }
            }
            if (!editor.commit()) throw IOException("Could not save restored settings")
        }
        return BackupOperationResult(true, "Settings restored")
    }

    private fun encodePreferences(values: Map<String, *>): ByteArray {
        val output = ByteArrayOutputStream()
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(output, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "map")
        for ((key, value) in values.toSortedMap()) {
            validatePreferenceKey(key)
            when (value) {
                is String -> {
                    checkString(value)
                    serializer.startTag(null, "string").attribute(null, "name", key).text(value).endTag(null, "string")
                }
                is Boolean -> serializer.startTag(null, "boolean").attribute(null, "name", key)
                    .attribute(null, "value", value.toString()).endTag(null, "boolean")
                is Int -> serializer.startTag(null, "int").attribute(null, "name", key)
                    .attribute(null, "value", value.toString()).endTag(null, "int")
                is Long -> serializer.startTag(null, "long").attribute(null, "name", key)
                    .attribute(null, "value", value.toString()).endTag(null, "long")
                is Float -> {
                    if (!value.isFinite()) throw IOException("A setting has an invalid number")
                    serializer.startTag(null, "float").attribute(null, "name", key)
                        .attribute(null, "value", value.toString()).endTag(null, "float")
                }
                is Set<*> -> {
                    if (value.size > MAX_PREF_VALUES) throw IOException("A setting contains too many values")
                    serializer.startTag(null, "set").attribute(null, "name", key)
                    value.map { it as? String ?: throw IOException("Unsupported setting value") }.sorted().forEach {
                        checkString(it)
                        serializer.startTag(null, "string").text(it).endTag(null, "string")
                    }
                    serializer.endTag(null, "set")
                }
                else -> throw IOException("A setting has an unsupported value type")
            }
        }
        serializer.endTag(null, "map")
        serializer.endDocument()
        serializer.flush()
        if (output.size() > MAX_ENTRY_BYTES) throw IOException("Settings backup is too large")
        return output.toByteArray()
    }

    private fun decodePreferences(bytes: ByteArray): Map<String, Any> {
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) throw IOException("Invalid preferences data")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(String(bytes, StandardCharsets.UTF_8)))
        if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "map") {
            throw IOException("Preferences data has an invalid root element")
        }
        val values = linkedMapOf<String, Any>()
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            val tag = parser.name
            val key = parser.getAttributeValue(null, "name") ?: throw IOException("A setting has no name")
            validatePreferenceKey(key)
            if (values.containsKey(key) || values.size >= MAX_PREF_VALUES) throw IOException("Duplicate or excessive settings")
            val value: Any = when (tag) {
                "string" -> readString(parser)
                "boolean" -> parser.getAttributeValue(null, "value")?.let {
                    when (it) { "true" -> true; "false" -> false; else -> throw IOException("Invalid Boolean setting") }
                } ?: throw IOException("Invalid Boolean setting")
                "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull() ?: throw IOException("Invalid integer setting")
                "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull() ?: throw IOException("Invalid long setting")
                "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?: throw IOException("Invalid decimal setting")
                "set" -> readStringSet(parser)
                else -> throw IOException("Backup contains an unsupported setting type")
            }
            values[key] = value
        }
        if (parser.eventType != XmlPullParser.END_TAG || parser.name != "map") throw IOException("Preferences data is incomplete")
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.TEXT -> if (parser.text.isNotBlank()) throw IOException("Preferences data contains trailing content")
                else -> throw IOException("Preferences data contains trailing content")
            }
        }
        return values
    }

    private fun readString(parser: XmlPullParser): String {
        val value = parser.nextText()
        checkString(value)
        return value
    }

    private fun readStringSet(parser: XmlPullParser): Set<String> {
        val values = linkedSetOf<String>()
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            if (parser.name != "string") throw IOException("Invalid string-set value")
            val value = parser.nextText()
            checkString(value)
            if (!values.add(value)) throw IOException("Duplicate value in string set")
            if (values.size > MAX_PREF_VALUES) throw IOException("String set is too large")
        }
        if (parser.eventType != XmlPullParser.END_TAG || parser.name != "set") throw IOException("String set is incomplete")
        return values
    }

    private fun validatePreferenceKey(key: String) {
        if (key.isBlank() || key.length > 256 || key.any { it == '\u0000' }) throw IOException("Invalid setting name")
    }

    private fun checkString(value: String) {
        if (value.length > MAX_STRING_LENGTH || value.indexOf('\u0000') >= 0) throw IOException("A setting value is invalid or too large")
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > limit) throw IOException("Backup is larger than the allowed limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun webDavRequest(
        endpoint: String,
        username: String,
        password: String,
        method: String,
        body: ByteArray?
    ): Triple<Int, String, ByteArray?> {
        val url = try { URL(endpoint.trim()) } catch (_: Exception) { throw IOException("Enter a valid WebDAV URL") }
        if (url.protocol != "https" || url.host.isNullOrBlank() || url.userInfo != null || url.ref != null) {
            throw IOException("WebDAV URL must be an HTTPS file URL without embedded credentials or a fragment")
        }
        if (username.contains(':')) throw IOException("Username cannot contain a colon")
        val connection = (url.openConnection() as? HttpURLConnection) ?: throw IOException("Unsupported WebDAV connection")
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/zip, application/octet-stream")
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val auth = "$username:$password".toByteArray(StandardCharsets.UTF_8)
                connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth, Base64.NO_WRAP))
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/zip")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { readBounded(it, MAX_ARCHIVE_BYTES) }
            val message = if (status in 200..299) "" else response?.let { bytes ->
                val text = String(bytes, StandardCharsets.UTF_8).replace(Regex("[\\r\\n\\t]+"), " ").take(240)
                if (text.isBlank()) "" else ": $text"
            } ?: ""
            return Triple(status, message, if (method == "GET" && status in 200..299) response else null)
        } finally {
            connection.disconnect()
        }
    }
}
