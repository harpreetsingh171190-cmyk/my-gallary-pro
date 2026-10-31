package com.mygallery.app

import android.content.Context

/** Manual order of photos inside a custom album. Items not yet ordered keep their date order after the ordered ones. */
object AlbumOrder {
    private const val PREFS = "album_order"

    private fun read(context: Context, albumId: String): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(albumId, "")
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun apply(context: Context, albumId: String, items: List<GalleryMedia>): List<GalleryMedia> {
        val order = read(context, albumId)
        if (order.isEmpty()) return items
        val rank = order.withIndex().associate { it.value to it.index }
        return items.sortedBy { rank[it.uri.toString()] ?: Int.MAX_VALUE }
    }

    /** Moves [item] one place earlier (delta -1) or later (delta +1) and saves the new order. */
    fun move(context: Context, albumId: String, current: List<GalleryMedia>, item: GalleryMedia, delta: Int): List<GalleryMedia> {
        val list = current.toMutableList()
        val i = list.indexOfFirst { it.uri == item.uri }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return current
        list[i] = list[j].also { list[j] = list[i] }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(albumId, list.joinToString("\n") { it.uri.toString() }).apply()
        return list
    }
}
