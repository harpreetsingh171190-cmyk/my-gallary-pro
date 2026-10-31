package com.mygallery.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSearchTest {

    private fun media(
        id: Long,
        name: String,
        mime: String = "image/jpeg",
        size: Long = 1_000L,
        date: Long = 1_000L,
        folder: String = "Camera",
    ) = GalleryMedia(id, Uri.parse("content://media/external/files/$id"), name, mime, size, date, folder)

    private val photoA = media(1, "beach.jpg", size = 500, date = 100, folder = "Camera")
    private val photoB = media(2, "Zoo.jpg", size = 3_000, date = 200, folder = "Camera")
    private val video = media(3, "clip.mp4", mime = "video/mp4", size = 200_000_000, date = 300, folder = "Movies")
    private val items = listOf(photoA, photoB, video)

    @Test
    fun photosFilterExcludesVideos() {
        val result = filterGalleryMedia(items, GalleryFilterState(mediaType = GalleryMediaTypeFilter.PHOTOS))
        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun videosFilterKeepsOnlyVideos() {
        val result = filterGalleryMedia(items, GalleryFilterState(mediaType = GalleryMediaTypeFilter.VIDEOS))
        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun dateRangeIncludesBothEndpoints() {
        val result = filterGalleryMedia(items, GalleryFilterState(dateRange = GalleryDateRange(100, 200)))
        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedDateRangeIsRejected() {
        GalleryDateRange(200, 100)
    }

    @Test
    fun folderFilterMatchesExactFolder() {
        val result = filterGalleryMedia(items, GalleryFilterState(folder = "Movies"))
        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun favoritesOnlyMatchesByUriString() {
        val favorites = setOf(photoB.uri.toString())
        val result = filterGalleryMedia(items, GalleryFilterState(favoritesOnly = true), favorites)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun missingSortKeepsInputOrder() {
        val reversed = items.reversed()
        val result = filterGalleryMedia(reversed, GalleryFilterState())
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun nameSortIsCaseInsensitive() {
        val result = sortGalleryMedia(items, GallerySortOption.NAME)
        assertEquals(listOf(3L, 1L, 2L), result.map { it.id }) // clip, beach, Zoo
    }

    @Test
    fun sizeSortIsLargestFirst() {
        val result = sortGalleryMedia(items, GallerySortOption.SIZE)
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun newestSortOrdersByDateDescending() {
        val result = sortGalleryMedia(items, GallerySortOption.NEWEST)
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    @Test
    fun recentCollectionUsesTrailingWindow() {
        val sample = listOf(media(1, "old", date = 1_000), media(2, "new", date = 9_500))
        val result = smartCollectionMedia(
            sample,
            SmartCollection.RECENT,
            nowEpochSeconds = 10_000,
            recentWindowSeconds = 1_000,
        )
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun largeFilesCollectionUsesThreshold() {
        val result = smartCollectionMedia(items, SmartCollection.LARGE_FILES, largeFileThresholdBytes = 1_000)
        assertEquals(listOf(3L, 2L), result.map { it.id })
    }

    @Test
    fun favoritesCollectionUsesUriString() {
        val result = smartCollectionMedia(items, SmartCollection.FAVORITES, favoriteUris = setOf(photoA.uri.toString()))
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRecentWindowIsRejected() {
        smartCollectionMedia(items, SmartCollection.RECENT, recentWindowSeconds = -1)
    }
}
