package com.mygallery.app

import android.graphics.Bitmap
import android.util.LruCache

/**
 * In-memory cache of thumbnails, sized to about 1/16 of the app heap. Scrolling back to a tile shows
 * its image at once instead of reloading it, which keeps grids smooth.
 */
object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}
