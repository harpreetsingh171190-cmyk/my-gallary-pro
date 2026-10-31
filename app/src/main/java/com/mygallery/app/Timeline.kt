package com.mygallery.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Timeline display modes: 0 = grouped by day, 1 = grouped by month, 2 = plain grid. */
sealed interface TimelineRow {
    data class Header(val key: String, val title: String, val items: List<GalleryMedia>) : TimelineRow
    data class Tile(val item: GalleryMedia) : TimelineRow
}

/** [items] must already be sorted newest first (MediaRepository.load does this). */
fun buildTimeline(items: List<GalleryMedia>, mode: Int): List<TimelineRow> {
    if (mode == 2) return items.map { TimelineRow.Tile(it) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val locale = Locale.getDefault()
    val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
    val dayNoYear = DateTimeFormatter.ofPattern("EEE, d MMM", locale)
    val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    fun dateOf(item: GalleryMedia): LocalDate = Instant.ofEpochSecond(item.date).atZone(zone).toLocalDate()
    fun keyOf(d: LocalDate): String = if (mode == 1) "%d-%02d".format(d.year, d.monthValue) else d.toString()

    val rows = ArrayList<TimelineRow>(items.size + 64)
    var index = 0
    while (index < items.size) {
        val first = dateOf(items[index])
        val key = keyOf(first)
        val group = ArrayList<GalleryMedia>()
        while (index < items.size && keyOf(dateOf(items[index])) == key) { group += items[index]; index++ }
        val title = if (mode == 1) monthFormat.format(first) else when {
            first == today -> "Today"
            first == today.minusDays(1) -> "Yesterday"
            first.year == today.year -> dayNoYear.format(first)
            else -> dayFormat.format(first)
        }
        rows += TimelineRow.Header(key, title, group)
        group.forEach { rows += TimelineRow.Tile(it) }
    }
    return rows
}

@Composable
fun TimelineGrid(
    items: List<GalleryMedia>,
    mode: Int,
    selectionMode: Boolean,
    selectedUris: Set<String>,
    onToggleSelect: (GalleryMedia) -> Unit,
    onSelectGroup: (List<GalleryMedia>, Boolean) -> Unit,
    onOpen: (GalleryMedia, List<GalleryMedia>) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(items, mode) { buildTimeline(items, mode) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // First row index of each year, for the year chips.
    val yearTargets = remember(rows) {
        val map = LinkedHashMap<String, Int>()
        rows.forEachIndexed { i, row -> if (row is TimelineRow.Header) map.putIfAbsent(row.key.take(4), i) }
        map
    }
    // First row index of each year-month, so tapping a chip scrolls straight to that month.
    val jumpTargets = remember(rows) {
        val map = LinkedHashMap<String, Int>()
        rows.forEachIndexed { i, row -> if (row is TimelineRow.Header) map.putIfAbsent(row.key.take(7), i) }
        map
    }
    Column(modifier) {
        if (yearTargets.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                yearTargets.forEach { (year, index) ->
                    AssistChip(
                        onClick = { scope.launch { gridState.animateScrollToItem(index) } },
                        label = { Text(year) },
                    )
                }
            }
        }
        if (jumpTargets.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                jumpTargets.forEach { (ym, index) ->
                    AssistChip(
                        onClick = { scope.launch { gridState.animateScrollToItem(index) } },
                        label = { Text(monthLabel(ym)) },
                    )
                }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(3), state = gridState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
        items(
            rows,
            key = { row -> when (row) { is TimelineRow.Header -> "h:" + row.key; is TimelineRow.Tile -> row.item.uri.toString() } },
            span = { row -> if (row is TimelineRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { row -> if (row is TimelineRow.Header) 0 else 1 }
        ) { row ->
            when (row) {
                is TimelineRow.Header -> {
                    val allSelected = row.items.all { it.uri.toString() in selectedUris }
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Text("${row.items.size} items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onSelectGroup(row.items, !allSelected) }) { Text(if (allSelected) "Deselect" else "Select") }
                    }
                }
                is TimelineRow.Tile -> {
                    val item = row.item
                    MediaTile(item, selectionMode, item.uri.toString() in selectedUris, onLongClick = { if (!selectionMode) onToggleSelect(item) }) {
                        if (selectionMode) onToggleSelect(item) else onOpen(item, items)
                    }
                }
            }
        }
    }
    }
}

private fun monthLabel(yearMonth: String): String =
    runCatching {
        java.time.YearMonth.parse(yearMonth).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
    }.getOrDefault(yearMonth)
