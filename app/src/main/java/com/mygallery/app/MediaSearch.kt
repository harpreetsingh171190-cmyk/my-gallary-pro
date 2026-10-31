package com.mygallery.app

/** Which media kinds are included in a gallery search. */
enum class GalleryMediaTypeFilter {
    ALL,
    PHOTOS,
    VIDEOS,
}

/** Sort keys shared by gallery search, albums, and smart collections. */
enum class GallerySortOption {
    NEWEST,
    OLDEST,
    NAME,
    SIZE,
}

/** Read-only, derived views over existing MediaStore records; no files are copied or re-indexed. */
enum class SmartCollection {
    RECENT,
    FAVORITES,
    PHOTOS,
    VIDEOS,
    LARGE_FILES,
}

private const val DEFAULT_RECENT_WINDOW_SECONDS = 30L * 24 * 60 * 60
const val DEFAULT_LARGE_FILE_THRESHOLD_BYTES = 100L * 1024 * 1024

/**
 * Optional inclusive bounds for [GalleryMedia.date], expressed in Unix epoch seconds.
 * GalleryMedia.date is stored in seconds by MediaRepository.
 */
data class GalleryDateRange(
    val startInclusiveEpochSeconds: Long? = null,
    val endInclusiveEpochSeconds: Long? = null,
) {
    init {
        require(
            startInclusiveEpochSeconds == null || endInclusiveEpochSeconds == null ||
                startInclusiveEpochSeconds <= endInclusiveEpochSeconds,
        ) { "The start of a date range must not be after its end." }
    }
}

/** Immutable controls for filtering a gallery list. A null folder means all folders. */
data class GalleryFilterState(
    val mediaType: GalleryMediaTypeFilter = GalleryMediaTypeFilter.ALL,
    val dateRange: GalleryDateRange? = null,
    val folder: String? = null,
    val favoritesOnly: Boolean = false,
    /** Null preserves the input order, matching the historical filter behavior. */
    val sort: GallerySortOption? = null,
)

/**
 * Filters [items] without changing their order. Favorite membership is determined by the
 * item's URI string, matching the set persisted by GalleryApp.
 */
fun filterGalleryMedia(
    items: List<GalleryMedia>,
    filters: GalleryFilterState = GalleryFilterState(),
    favoriteUris: Set<String> = emptySet(),
): List<GalleryMedia> {
    val range = filters.dateRange
    val filtered = items.filter { item ->
        val matchesType = when (filters.mediaType) {
            GalleryMediaTypeFilter.ALL -> true
            GalleryMediaTypeFilter.PHOTOS -> !item.isVideo
            GalleryMediaTypeFilter.VIDEOS -> item.isVideo
        }
        val matchesStart = range?.startInclusiveEpochSeconds?.let { item.date >= it } ?: true
        val matchesEnd = range?.endInclusiveEpochSeconds?.let { item.date <= it } ?: true
        val matchesFolder = filters.folder == null || item.folder == filters.folder
        val matchesFavorite = !filters.favoritesOnly || item.uri.toString() in favoriteUris
        matchesType && matchesStart && matchesEnd && matchesFolder && matchesFavorite
    }
    return filters.sort?.let { sortGalleryMedia(filtered, it) } ?: filtered
}

/** Sort an in-memory MediaStore result without querying or moving its underlying media. */
fun sortGalleryMedia(items: Iterable<GalleryMedia>, sort: GallerySortOption): List<GalleryMedia> = when (sort) {
    GallerySortOption.NEWEST -> items.sortedWith(compareByDescending<GalleryMedia> { it.date }.thenBy { it.name.lowercase() })
    GallerySortOption.OLDEST -> items.sortedWith(compareBy<GalleryMedia> { it.date }.thenBy { it.name.lowercase() })
    GallerySortOption.NAME -> items.sortedWith(compareBy<GalleryMedia> { it.name.lowercase() }.thenBy { it.date })
    GallerySortOption.SIZE -> items.sortedWith(compareByDescending<GalleryMedia> { it.size }.thenByDescending { it.date })
}

/**
 * Resolve a smart collection from already-loaded media. Favorites use the same URI-string identity
 * as GalleryApp. Recent means the trailing 30 days by default; large files default to at least
 * 100 MiB. Parameters are injectable for predictable callers/tests.
 */
fun smartCollectionMedia(
    items: Iterable<GalleryMedia>,
    collection: SmartCollection,
    favoriteUris: Set<String> = emptySet(),
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    recentWindowSeconds: Long = DEFAULT_RECENT_WINDOW_SECONDS,
    largeFileThresholdBytes: Long = DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
    sort: GallerySortOption = GallerySortOption.NEWEST,
): List<GalleryMedia> {
    require(recentWindowSeconds >= 0) { "Recent window cannot be negative" }
    require(largeFileThresholdBytes >= 0) { "Large-file threshold cannot be negative" }
    val filtered = items.filter { item ->
        when (collection) {
            SmartCollection.RECENT -> item.date in (nowEpochSeconds - recentWindowSeconds)..nowEpochSeconds
            SmartCollection.FAVORITES -> item.uri.toString() in favoriteUris
            SmartCollection.PHOTOS -> !item.isVideo
            SmartCollection.VIDEOS -> item.isVideo
            SmartCollection.LARGE_FILES -> item.size >= largeFileThresholdBytes
        }
    }
    return sortGalleryMedia(filtered, sort)
}
