package com.mygallery.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/** Saves a scaled JPEG copy into Pictures/MyGallery. The original is not changed. */
fun saveSmallerCopy(context: Context, item: GalleryMedia, maxEdge: Int = 1080): Boolean {
    if (item.isVideo) return false
    val bitmap = decodeForViewer(context, item.uri, maxEdge) ?: return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "small_" + item.name.substringBeforeLast('.') + ".jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyGallery")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("no output")
        out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        true
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Reads GPS coordinates from the photo's EXIF, or null when there are none. */
fun photoLatLong(context: Context, item: GalleryMedia): DoubleArray? {
    val original = runCatching {
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri))?.use { ExifInterface(it).latLong }
    }.getOrNull()
    if (original != null) return original
    return runCatching {
        context.contentResolver.openInputStream(item.uri)?.use { ExifInterface(it).latLong }
    }.getOrNull()
}

fun mapIntent(lat: Double, lng: Double): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))


/** Saves a copy rotated clockwise by [degrees]. The original is not changed. */
fun saveRotatedCopy(context: Context, item: GalleryMedia, degrees: Float = 90f): Boolean {
    if (item.isVideo) return false
    val src = decodeForViewer(context, item.uri, 2560) ?: return false
    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    return writeJpegCopy(context, rotated, "rot_" + item.name.substringBeforeLast('.') + ".jpg")
}

private fun writeJpegCopy(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyGallery")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("no output")
        out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Remembers which photo is the cover of each custom album. */
object AlbumCovers {
    private const val PREFS = "album_covers"

    fun set(context: Context, albumId: String, uri: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(albumId, uri).apply()
    }

    fun get(context: Context, albumId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(albumId, null)
}


/** Video durations in milliseconds, keyed by MediaStore id. */
fun loadVideoDurations(context: Context): Map<Long, Long> {
    val map = HashMap<Long, Long>()
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DURATION),
        null, null, null,
    )?.use { cursor ->
        while (cursor.moveToNext()) map[cursor.getLong(0)] = cursor.getLong(1)
    }
    return map
}
