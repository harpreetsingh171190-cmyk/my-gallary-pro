package com.mygallery.app

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable, read-only smart-collection browser. Collections are computed from the caller's
 * already-loaded MediaStore records and favorite URI set; no duplicate media is created.
 * Hosts can connect [onMediaClick] to their existing viewer/navigation flow.
 */
@Composable
fun SmartCollectionsUi(
    items: List<GalleryMedia>,
    favoriteUris: Set<String>,
    modifier: Modifier = Modifier,
    onMediaClick: (GalleryMedia) -> Unit = {},
) {
    var collection by remember { mutableStateOf(SmartCollection.RECENT) }
    var sort by remember { mutableStateOf(GallerySortOption.NEWEST) }
    val collectionCounts = remember(items, favoriteUris) {
        SmartCollection.values().associateWith { kind ->
            smartCollectionMedia(items, kind, favoriteUris).size
        }
    }
    val visibleItems = remember(items, favoriteUris, collection, sort) {
        smartCollectionMedia(items, collection, favoriteUris, sort = sort)
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.s_smart_collections), style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SmartCollection.values().toList(), key = { it.name }) { kind ->
                val label = "${kind.displayName()} (${collectionCounts[kind] ?: 0})"
                if (kind == collection) {
                    Button(onClick = { collection = kind }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { collection = kind }) { Text(label) }
                }
            }
        }
        Text(stringResource(R.string.s_sort), style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GallerySortOption.values().forEach { option ->
                if (option == sort) {
                    Button(onClick = { sort = option }) { Text(option.displayName()) }
                } else {
                    OutlinedButton(onClick = { sort = option }) { Text(option.displayName()) }
                }
            }
        }
        if (visibleItems.isEmpty()) {
            Text("No ${collection.displayName().lowercase()} media found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(visibleItems, key = { it.uri.toString() }) { media ->
                    Card(Modifier.fillMaxWidth().clickable { onMediaClick(media) }) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(media.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                "${if (media.isVideo) "Video" else "Photo"} · ${media.folder} · ${formatMediaSize(media.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SmartCollection.displayName(): String = when (this) {
    SmartCollection.RECENT -> "Recent"
    SmartCollection.FAVORITES -> "Favorites"
    SmartCollection.PHOTOS -> "Photos"
    SmartCollection.VIDEOS -> "Videos"
    SmartCollection.LARGE_FILES -> "Large files"
}

private fun GallerySortOption.displayName(): String = when (this) {
    GallerySortOption.NEWEST -> "Newest"
    GallerySortOption.OLDEST -> "Oldest"
    GallerySortOption.NAME -> "Name"
    GallerySortOption.SIZE -> "Largest"
}

private fun formatMediaSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
