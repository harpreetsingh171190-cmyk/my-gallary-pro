package com.mygallery.app

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.app.RecoverableSecurityException
import android.util.Base64
import java.io.IOException
import java.util.UUID

/** A user-defined grouping. Items are stored under a dedicated MediaStore relative path. */
data class CustomAlbum(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis
) {
    /** Stable path: renaming an album does not invalidate its existing media. */
    val relativePath: String get() = "Pictures/MyGalleryAlbums/album_$id/"
}

enum class GalleryMediaType { ALL, PHOTOS, VIDEOS }
enum class GalleryDateOrder { NEWEST_FIRST, OLDEST_FIRST }

/** Persistent album definitions stored in app-private SharedPreferences. */
object CustomAlbums {
    private const val PREFS = "custom_gallery_albums"
    private const val KEY_ALBUMS = "definitions_v1"
    private val lock = Any()

    fun list(context: Context): List<CustomAlbum> = synchronized(lock) {
        read(context).sortedBy { it.name.trim().lowercase() }
    }

    fun create(context: Context, name: String): CustomAlbum {
        val cleanName = validateName(name)
        synchronized(lock) {
            val albums = read(context)
            require(albums.none { it.name.equals(cleanName, ignoreCase = true) }) { "An album with that name already exists" }
            val now = System.currentTimeMillis()
            val album = CustomAlbum(UUID.randomUUID().toString(), cleanName, now, now)
            write(context, albums + album)
            return album
        }
    }

    fun rename(context: Context, albumId: String, newName: String): CustomAlbum {
        val cleanName = validateName(newName)
        synchronized(lock) {
            val albums = read(context)
            val target = albums.firstOrNull { it.id == albumId } ?: throw NoSuchElementException("Album not found")
            require(albums.none { it.id != albumId && it.name.equals(cleanName, ignoreCase = true) }) { "An album with that name already exists" }
            val updated = target.copy(name = cleanName, updatedAtMillis = System.currentTimeMillis())
            write(context, albums.map { if (it.id == albumId) updated else it })
            return updated
        }
    }

    /** Removes only the album definition; media files are deliberately left untouched. */
    fun delete(context: Context, albumId: String): Boolean = synchronized(lock) {
        val albums = read(context)
        if (albums.none { it.id == albumId }) return@synchronized false
        write(context, albums.filterNot { it.id == albumId })
        true
    }

    fun find(context: Context, albumId: String): CustomAlbum? = list(context).firstOrNull { it.id == albumId }

    private fun validateName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Album name cannot be empty" }
        require(clean.length <= 80) { "Album name must be 80 characters or fewer" }
        return clean
    }

    private fun read(context: Context): List<CustomAlbum> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALBUMS, null) ?: return emptyList()
        return encoded.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val fields = line.split('\t')
                CustomAlbum(decode(fields[0]), decode(fields[1]), fields[2].toLong(), fields[3].toLong())
            }.getOrNull()
        }.toList()
    }

    private fun write(context: Context, albums: List<CustomAlbum>) {
        val value = albums.joinToString("\n") { listOf(encode(it.id), encode(it.name), it.createdAtMillis, it.updatedAtMillis).joinToString("\t") }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ALBUMS, value).commit()) {
            "Could not save albums"
        }
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}

/** Result for copy/move. For a move, the caller must launch [deleteConsentIntentSender] and only
 * consider it complete after the user approves and [deleteSourcesAfterConsent] succeeds. */
data class AlbumMediaOperationResult(
    val copiedItems: List<Uri>,
    val sourceItems: List<Uri>,
    val deleteConsentIntentSender: IntentSender? = null,
    val failureMessages: List<String> = emptyList()
) {
    val hasConsentPending: Boolean get() = deleteConsentIntentSender != null
}

/** Copies selected media into the album using MediaStore IS_PENDING + RELATIVE_PATH. This works
 * within scoped storage and does not access raw filesystem paths. On move, sources are preserved
 * until the UI receives explicit user consent to delete them. Call from a worker dispatcher. */
fun copyMediaToAlbum(context: Context, items: List<GalleryMedia>, album: CustomAlbum): AlbumMediaOperationResult {
    val resolver = context.contentResolver
    val copied = mutableListOf<Uri>()
    val copiedSources = mutableListOf<Uri>()
    val failures = mutableListOf<String>()
    items.distinctBy { it.uri }.forEach { item ->
        var destination: Uri? = null
        try {
            val collection = if (item.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, item.name.ifBlank { "media_${item.id}" })
                put(MediaColumns.MIME_TYPE, item.mime.ifBlank { if (item.isVideo) "video/*" else "image/*" })
                put(MediaColumns.RELATIVE_PATH, album.relativePath)
                put(MediaColumns.IS_PENDING, 1)
            }
            destination = resolver.insert(collection, values) ?: throw IOException("MediaStore rejected the new item")
            val input = resolver.openInputStream(item.uri) ?: throw IOException("Could not open ${item.name}")
            val output = resolver.openOutputStream(destination, "w") ?: throw IOException("Could not write ${item.name}")
            input.use { source -> output.use { target -> source.copyTo(target) } }
            val published = ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }
            if (resolver.update(destination, published, null, null) <= 0) throw IOException("Could not publish ${item.name}")
            copied += destination
            copiedSources += item.uri
        } catch (e: Exception) {
            destination?.let { runCatching { resolver.delete(it, null, null) } }
            failures += "${item.name}: ${e.message ?: "copy failed"}"
        }
    }
    return AlbumMediaOperationResult(copied, copiedSources, failureMessages = failures)
}

/** Builds a system consent request for deleting originals. Available on API 30+. */
fun createDeleteConsentIntentSender(context: Context, sourceUris: List<Uri>): IntentSender? {
    if (sourceUris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return MediaStore.createDeleteRequest(context.contentResolver, sourceUris.distinct()).intentSender
}

/** Retry source deletion only after the system consent flow succeeds. API 29 may throw a
 * RecoverableSecurityException; in that case its system-provided action is returned to the caller. */
fun deleteSourcesAfterConsent(context: Context, sourceUris: List<Uri>): AlbumDeleteResult {
    val resolver = context.contentResolver
    var deleted = 0
    for (uri in sourceUris.distinct()) {
        try {
            if (resolver.delete(uri, null, null) > 0) deleted++
        } catch (e: RecoverableSecurityException) {
            return AlbumDeleteResult(deleted, e.userAction.actionIntent.intentSender, uri)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sender = runCatching { createDeleteConsentIntentSender(context, sourceUris) }.getOrNull()
                return AlbumDeleteResult(deleted, sender, uri)
            }
            throw e
        }
    }
    return AlbumDeleteResult(deleted)
}

data class AlbumDeleteResult(val deletedCount: Int, val consentIntentSender: IntentSender? = null, val blockedUri: Uri? = null)

/** MediaStore query for the contents of one album. */
fun loadAlbumMedia(context: Context, album: CustomAlbum): List<GalleryMedia> =
    MediaRepository.load(context).filter { media ->
        val columns = arrayOf(MediaColumns.RELATIVE_PATH)
        runCatching {
            context.contentResolver.query(media.uri, columns, null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0)?.trimEnd('/') == album.relativePath.trimEnd('/')
            } ?: false
        }.getOrDefault(false)
    }

/** Filter and sort already-loaded gallery records without another MediaStore query. Dates use the
 * same epoch-seconds convention as GalleryMedia.date / MediaRepository. */
fun filterAndSortGalleryMedia(
    items: Iterable<GalleryMedia>,
    dateOrder: GalleryDateOrder = GalleryDateOrder.NEWEST_FIRST,
    folder: String? = null,
    mediaType: GalleryMediaType = GalleryMediaType.ALL,
    fromDateInclusive: Long? = null,
    throughDateInclusive: Long? = null,
    sort: GallerySortOption? = null,
): List<GalleryMedia> {
    val filtered = items.asSequence()
        .filter { folder == null || it.folder.equals(folder, ignoreCase = true) }
        .filter { mediaType == GalleryMediaType.ALL || (mediaType == GalleryMediaType.VIDEOS) == it.isVideo }
        .filter { fromDateInclusive == null || it.date >= fromDateInclusive }
        .filter { throughDateInclusive == null || it.date <= throughDateInclusive }
    val matching = filtered.toList()
    return sort?.let { sortGalleryMedia(matching, it) } ?: when (dateOrder) {
        GalleryDateOrder.NEWEST_FIRST -> matching.sortedByDescending { it.date }
        GalleryDateOrder.OLDEST_FIRST -> matching.sortedBy { it.date }
    }
}

fun galleryFolders(items: Iterable<GalleryMedia>): List<String> = items.map { it.folder }.distinct().sortedBy { it.lowercase() }
