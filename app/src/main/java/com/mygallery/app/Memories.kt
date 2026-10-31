package com.mygallery.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class MemoryKind { ON_THIS_DAY, TRIP, MONTH }

/** One memory card. Only the fields for its [kind] are set. */
data class Memory(
    val kind: MemoryKind,
    val items: List<GalleryMedia>,
    val yearsAgo: Int = 0,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val month: YearMonth? = null,
)

private const val TRIP_GAP_SECONDS = 2L * 24 * 60 * 60
private const val TRIP_MIN_ITEMS = 30
private const val MONTH_MIN_ITEMS = 5
private const val MAX_MONTHS = 24
private const val SLIDE_MILLIS = 4_000L

/**
 * Builds memories from already-loaded MediaStore records; nothing is copied or re-indexed.
 * - ON_THIS_DAY: same day and month in earlier years.
 * - TRIP: bursts of photos with no gap longer than two days. Detected from dates only, not location.
 * - MONTH: months with enough photos, newest first.
 */
fun buildMemories(items: List<GalleryMedia>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Memory> {
    fun dateOf(item: GalleryMedia): LocalDate = Instant.ofEpochSecond(item.date).atZone(zone).toLocalDate()
    val sorted = items.sortedBy { it.date }

    val onThisDay = sorted
        .filter { val d = dateOf(it); d.year < today.year && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth }
        .groupBy { today.year - dateOf(it).year }
        .toSortedMap()
        .map { (years, group) -> Memory(MemoryKind.ON_THIS_DAY, group, yearsAgo = years) }

    val trips = mutableListOf<Memory>()
    var current = mutableListOf<GalleryMedia>()
    fun flush() {
        if (current.size >= TRIP_MIN_ITEMS) {
            val first = dateOf(current.first())
            val last = dateOf(current.last())
            if (first != last) trips += Memory(MemoryKind.TRIP, current.toList(), start = first, end = last)
        }
        current = mutableListOf()
    }
    var previous: Long? = null
    for (item in sorted) {
        if (previous != null && item.date - previous > TRIP_GAP_SECONDS) flush()
        current += item
        previous = item.date
    }
    flush()

    val months = sorted
        .groupBy { YearMonth.from(dateOf(it)) }
        .filter { it.value.size >= MONTH_MIN_ITEMS }
        .toSortedMap(compareByDescending<YearMonth> { it })
        .map { (month, group) -> Memory(MemoryKind.MONTH, group, month = month) }
        .take(MAX_MONTHS)

    return onThisDay + trips.sortedByDescending { it.items.last().date } + months
}

/** Memories tab: cards for each memory, each opening a full-screen slideshow. */
@Composable
fun MemoriesUi(media: List<GalleryMedia>, modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    // Grouping a large library is real work, so it runs off the main thread.
    val memories by produceState<List<Memory>>(initialValue = emptyList(), media, today) {
        value = withContext(Dispatchers.IO) { buildMemories(media, today) }
    }
    var open by remember { mutableStateOf<Memory?>(null) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.s_memories), style = MaterialTheme.typography.headlineSmall)
        if (memories.isEmpty()) {
            Text(stringResource(R.string.s_no_memories), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(memories) { memory ->
                    Card(Modifier.fillMaxWidth().clickable { open = memory }) {
                        Column(Modifier.padding(14.dp)) {
                            MediaThumb(memory.items.first(), Modifier.padding(bottom = 8.dp))
                            Text(memoryTitle(memory), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.s_items_count, memory.items.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { open = memory }, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.s_play_slideshow))
                            }
                        }
                    }
                }
            }
        }
    }
    open?.let { memory -> SlideshowDialog(memory.items) { open = null } }
}

@Composable
private fun memoryTitle(memory: Memory): String {
    val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    return when (memory.kind) {
        MemoryKind.ON_THIS_DAY ->
            stringResource(R.string.s_on_this_day) + " · " + stringResource(R.string.s_years_ago, memory.yearsAgo)
        MemoryKind.TRIP ->
            stringResource(R.string.s_trips_events) + ": " + (memory.start?.format(dayFormat) ?: "") +
                " – " + (memory.end?.format(dayFormat) ?: "")
        MemoryKind.MONTH ->
            memory.month?.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())) ?: ""
    }
}

/** Full-screen photo slideshow. Advances every few seconds; tap to pause or resume. Videos are skipped. */
@Composable
fun SlideshowDialog(items: List<GalleryMedia>, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val slidePrefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var shuffled by remember { mutableStateOf(slidePrefs.getBoolean("slide_shuffle", false)) }
    val photos = remember(items, shuffled) { items.let { if (shuffled) it.shuffled() else it } }
    var index by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var intervalMs by remember { mutableStateOf(slidePrefs.getLong("slide_ms", SLIDE_MILLIS)) }
    val item = photos.getOrNull(index)
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(playing, index, photos.size, intervalMs) {
        // Photos advance on a timer; videos advance when they finish playing.
        if (playing && photos.size > 1 && photos.getOrNull(index)?.isVideo != true) {
            delay(intervalMs)
            index = (index + 1) % photos.size
        }
    }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item?.uri) {
        bitmap = item?.takeIf { !it.isVideo }?.let { withContext(Dispatchers.IO) { decodeForViewer(context, it.uri, 1600) } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { playing = !playing }) {
            if (item != null && item.isVideo) {
                key(item.uri) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(item.uri)
                                setOnCompletionListener { index = (index + 1) % photos.size }
                            }
                        },
                        update = { view -> if (playing) view.start() else view.pause() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                bitmap?.let {
                    Image(it.asImageBitmap(), item?.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.s_close), tint = Color.White)
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(2_000L, 4_000L, 8_000L).forEach { ms ->
                    TextButton(onClick = { intervalMs = ms; slidePrefs.edit().putLong("slide_ms", ms).apply() }) {
                        Text(
                            stringResource(R.string.s_seconds, (ms / 1000).toInt()),
                            color = if (ms == intervalMs) Color.White else Color(0xFFAAAAAA),
                        )
                    }
                }
                TextButton(onClick = { shuffled = !shuffled; index = 0; slidePrefs.edit().putBoolean("slide_shuffle", shuffled).apply() }) {
                    Text(stringResource(R.string.s_shuffle), color = if (shuffled) Color.White else Color(0xFFAAAAAA))
                }
            }
            Text(
                "${if (photos.isEmpty()) 0 else index + 1} / ${photos.size}",
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}
