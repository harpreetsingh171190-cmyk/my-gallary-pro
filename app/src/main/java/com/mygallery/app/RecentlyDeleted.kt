package com.mygallery.app

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentUris
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Items in the system trash (Android 11+). Returns an empty list on older versions. */
fun loadTrashed(context: Context): List<GalleryMedia> {
    if (Build.VERSION.SDK_INT < 30) return emptyList()
    val result = mutableListOf<GalleryMedia>()
    val columns = arrayOf(
        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DATE_TAKEN,
    )
    val args = Bundle().apply {
        putString(
            ContentResolver.QUERY_ARG_SQL_SELECTION,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?",
        )
        putStringArray(
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ),
        )
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
    }
    context.contentResolver.query(MediaStore.Files.getContentUri("external"), columns, args, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val name = cursor.getString(1) ?: "Untitled"
            val mime = cursor.getString(2) ?: "application/octet-stream"
            val base = if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val taken = cursor.getLong(7)
            val date = if (taken > 0) taken / 1000 else cursor.getLong(4)
            result += GalleryMedia(id, ContentUris.withAppendedId(base, id), name, mime,
                cursor.getLong(3), date, cursor.getString(5) ?: "Other")
        }
    }
    result.sortByDescending { it.date }
    return result
}

/** Lists trashed photos and videos with a restore action. Restoring asks Android for consent first. */
@Composable
fun RecentlyDeletedUi(items: List<GalleryMedia>, onRestore: (List<GalleryMedia>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.s_recently_deleted), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.s_trash_size, items.sumOf { it.size } / (1024 * 1024)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            Build.VERSION.SDK_INT < 30 -> Text(stringResource(R.string.s_needs_android_11), color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.isEmpty() -> Text(stringResource(R.string.s_no_deleted), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Button(onClick = { onRestore(items) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.s_restore_all))
                }
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.uri.toString() }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                MediaThumb(item, Modifier.padding(end = 10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text("${item.size / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onRestore(listOf(item)) }) { Text(stringResource(R.string.s_restore)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
