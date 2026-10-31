package com.mygallery.app.pro

import com.mygallery.app.GalleryMedia
import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmartGallery {
    fun timeline(items: List<GalleryMedia>): Map<String,List<GalleryMedia>> {
        val format = SimpleDateFormat("yyyy-MM", Locale.ROOT)
        return items.groupBy { format.format(Date(it.date * 1000)) }
    }
    fun page(items: List<GalleryMedia>, page: Int, size: Int = 60): List<GalleryMedia> = items.drop(page.coerceAtLeast(0)*size).take(size)
    fun metadata(context: Context, uri: Uri): Map<String,String> = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { stream ->
            val exif = ExifInterface(stream)
            listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH)
                .mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
        }
    }.getOrDefault(emptyMap())
    fun locations(context: Context, items: List<GalleryMedia>): List<Pair<GalleryMedia,DoubleArray>> = items.filter { !it.isVideo }.mapNotNull { item ->
        runCatching { context.contentResolver.openInputStream(item.uri)!!.use { input ->
            ExifInterface(input).latLong?.let { item to it }
        } }.getOrNull()
    }
}
