package com.mygallery.app

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small square preview of a photo or video, loaded off the main thread with the system thumbnailer. */
@Composable
fun MediaThumb(item: GalleryMedia, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cacheKey = "${item.uri}#192"
    val bitmap by produceState<Bitmap?>(initialValue = ThumbCache.get(cacheKey), item.uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(item.uri, Size(192, 192), null) }.getOrNull()
            }
            value?.let { ThumbCache.put(cacheKey, it) }
        }
    }
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
