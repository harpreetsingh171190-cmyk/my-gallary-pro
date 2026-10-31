package com.mygallery.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private const val DAY_SECONDS = 86_400L

/** Screenshots are recognised by the folder name MediaStore reports for them (e.g. "Screenshots"). */
/** Files of 100 MB or more, photos and videos alike. */
fun isLargeFile(item: GalleryMedia): Boolean = item.size >= 100L * 1024 * 1024

fun isScreenshot(item: GalleryMedia): Boolean =
    !item.isVideo && item.folder.contains("screenshot", ignoreCase = true)

/**
 * Lists screenshots oldest first so old ones can be selected and sent to the system trash together.
 * Nothing is deleted here directly; [onTrash] goes through the same Android trash consent as the grid.
 */
@Composable
fun ScreenshotCleanupUi(
    media: List<GalleryMedia>,
    onTrash: (List<GalleryMedia>) -> Unit,
    modifier: Modifier = Modifier,
    titleRes: Int = R.string.s_screenshots,
    hintRes: Int = R.string.s_screenshot_cleanup_hint,
    predicate: (GalleryMedia) -> Boolean = ::isScreenshot,
    showAgeFilter: Boolean = true,
    bySize: Boolean = false,
) {
    val screenshots = remember(media, predicate, bySize) {
        media.filter(predicate).let { if (bySize) it.sortedByDescending { x -> x.size } else it.sortedBy { x -> x.date } }
    }
    var minAgeDays by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val now = System.currentTimeMillis() / 1000
    val visible = remember(screenshots, minAgeDays) {
        screenshots.filter { minAgeDays == 0 || it.date <= now - minAgeDays * DAY_SECONDS }
    }
    val chosen = visible.filter { it.uri.toString() in selected }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(hintRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showAgeFilter) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.s_all, 30 to R.string.s_days_30, 90 to R.string.s_days_90, 365 to R.string.s_days_365).forEach { (days, label) ->
                if (days == minAgeDays) {
                    Button(onClick = { minAgeDays = days }) { Text(stringResource(label)) }
                } else {
                    OutlinedButton(onClick = { minAgeDays = days }) { Text(stringResource(label)) }
                }
            }
        }
        Text(
            "${stringResource(R.string.s_selected)}: ${chosen.size} · ${chosen.sumOf { it.size } / (1024 * 1024)} MB",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selected = visible.map { it.uri.toString() }.toSet() },
                enabled = visible.isNotEmpty(),
            ) { Text(stringResource(R.string.s_select_all)) }
            OutlinedButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.s_select_none)) }
        }
        Button(
            onClick = { onTrash(chosen); selected = emptySet() },
            enabled = chosen.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.s_move_selected_to_trash)) }

        if (visible.isEmpty()) {
            Text(stringResource(R.string.s_no_screenshots), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.uri.toString() }) { shot ->
                    val key = shot.uri.toString()
                    val isSelected = key in selected
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (isSelected) selected - key else selected + key
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                            MediaThumb(shot, Modifier.padding(horizontal = 6.dp))
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(shot.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    "${dateFormat.format(Date(shot.date * 1000))} · ${shot.size / 1024} KB",
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
}
