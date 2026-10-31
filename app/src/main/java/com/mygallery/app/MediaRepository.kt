package com.mygallery.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest

data class GalleryMedia(val id: Long, val uri: Uri, val name: String, val mime: String, val size: Long, val date: Long, val folder: String) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

object MediaRepository {
    fun load(context: Context): List<GalleryMedia> {
        val result = mutableListOf<GalleryMedia>()
        val columns = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATE_TAKEN)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
        context.contentResolver.query(MediaStore.Files.getContentUri("external"), columns, selection, args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "Untitled"
                val mime = cursor.getString(2) ?: "application/octet-stream"
                val base = if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                // Prefer the capture time (ms) so the timeline follows when a photo was taken, not when it was copied.
                val taken = cursor.getLong(7)
                val date = if (taken > 0) taken / 1000 else cursor.getLong(4)
                result += GalleryMedia(id, ContentUris.withAppendedId(base, id), name, mime,
                    cursor.getLong(3), date, cursor.getString(5) ?: "Other")
            }
        }
        result.sortByDescending { it.date }
        return result
    }
    fun duplicates(context: Context, items: List<GalleryMedia>): List<List<GalleryMedia>> {
        val groups = mutableMapOf<String, MutableList<GalleryMedia>>()
        items.filter { it.size > 0 }.groupBy { it.size }.filterValues { it.size > 1 }.values.flatten().forEach { item ->
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    val key = digest.digest().joinToString("") { "%02x".format(it) }
                    groups.getOrPut(key) { mutableListOf() }.add(item)
                }
            } catch (_: Exception) { }
        }
        return groups.values.filter { it.size > 1 }
    }
}
