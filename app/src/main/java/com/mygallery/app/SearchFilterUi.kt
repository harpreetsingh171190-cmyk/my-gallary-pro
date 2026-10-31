package com.mygallery.app

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val isoDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    .withResolverStyle(ResolverStyle.STRICT)

/**
 * Accessible, controlled filter controls for [filterGalleryMedia]. Dates use ISO-8601
 * calendar dates (YYYY-MM-DD); the starting date includes its first second and the ending
 * date includes its final second. Pass distinct folder names (usually from the loaded list).
 */
@Composable
fun SearchFilterControls(
    state: GalleryFilterState,
    folders: List<String>,
    onStateChange: (GalleryFilterState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val startBound = state.dateRange?.startInclusiveEpochSeconds
    val endBound = state.dateRange?.endInclusiveEpochSeconds
    var startText by remember(startBound) { mutableStateOf(startBound?.let(::formatIsoDate).orEmpty()) }
    var endText by remember(endBound) { mutableStateOf(endBound?.let(::formatIsoDate).orEmpty()) }
    val parsedStart = parseStartDate(startText)
    val parsedEnd = parseEndDate(endText)
    val dateRangeIsValid = (startText.isBlank() || parsedStart != null) &&
        (endText.isBlank() || parsedEnd != null) &&
        (parsedStart == null || parsedEnd == null || parsedStart <= parsedEnd)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.s_media_type))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                GalleryMediaTypeFilter.ALL to "All",
                GalleryMediaTypeFilter.PHOTOS to "Photos",
                GalleryMediaTypeFilter.VIDEOS to "Videos",
            ).forEach { (type, label) ->
                FilterChip(
                    selected = state.mediaType == type,
                    onClick = { onStateChange(state.copy(mediaType = type)) },
                    label = { Text(label) },
                )
            }
        }

        Text(stringResource(R.string.s_sort))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                null to "Default",
                GallerySortOption.NEWEST to "Newest",
                GallerySortOption.OLDEST to "Oldest",
                GallerySortOption.NAME to "Name",
                GallerySortOption.SIZE to "Largest",
            ).forEach { (option, label) ->
                FilterChip(
                    selected = state.sort == option,
                    onClick = { onStateChange(state.copy(sort = option)) },
                    label = { Text(label) },
                )
            }
        }

        Text(stringResource(R.string.s_date_range))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = { value ->
                    startText = value
                    val start = parseStartDate(value)
                    val end = parseEndDate(endText)
                    if ((value.isBlank() || start != null) && (endText.isBlank() || end != null) &&
                        (start == null || end == null || start <= end)
                    ) {
                        onStateChange(state.copy(dateRange = makeDateRange(start, end)))
                    }
                },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.s_from_yyyy_mm_dd)) },
                placeholder = { Text(stringResource(R.string.s_any_date)) },
                singleLine = true,
                isError = startText.isNotBlank() && (parsedStart == null || !dateRangeIsValid),
                supportingText = if (startText.isNotBlank() && (parsedStart == null || !dateRangeIsValid)) {
                    { Text(stringResource(R.string.s_enter_a_valid_date_not_later_than_the_end_date)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { value ->
                    endText = value
                    val start = parseStartDate(startText)
                    val end = parseEndDate(value)
                    if ((startText.isBlank() || start != null) && (value.isBlank() || end != null) &&
                        (start == null || end == null || start <= end)
                    ) {
                        onStateChange(state.copy(dateRange = makeDateRange(start, end)))
                    }
                },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.s_through_yyyy_mm_dd)) },
                placeholder = { Text(stringResource(R.string.s_any_date)) },
                singleLine = true,
                isError = endText.isNotBlank() && (parsedEnd == null || !dateRangeIsValid),
                supportingText = if (endText.isNotBlank() && (parsedEnd == null || !dateRangeIsValid)) {
                    { Text(stringResource(R.string.s_enter_a_valid_date_not_earlier_than_the_start_date)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        }

        var folderMenuExpanded by remember { mutableStateOf(false) }
        Text(stringResource(R.string.s_folder))
        androidx.compose.foundation.layout.Box {
            TextButton(
                onClick = { folderMenuExpanded = true },
                modifier = Modifier.semantics {
                    contentDescription = "Choose folder; currently ${state.folder ?: "all folders"}"
                },
            ) {
                Text(state.folder ?: "All folders")
            }
            DropdownMenu(
                expanded = folderMenuExpanded,
                onDismissRequest = { folderMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.s_all_folders)) },
                    onClick = {
                        folderMenuExpanded = false
                        onStateChange(state.copy(folder = null))
                    },
                )
                folders.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }).forEach { folderName ->
                    DropdownMenuItem(
                        text = { Text(folderName) },
                        onClick = {
                            folderMenuExpanded = false
                            onStateChange(state.copy(folder = folderName))
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.s_favorites_only))
            Switch(
                checked = state.favoritesOnly,
                onCheckedChange = { onStateChange(state.copy(favoritesOnly = it)) },
                modifier = Modifier.semantics { contentDescription = stringResource(R.string.s_show_favorites_only) },
            )
        }

        TextButton(
            onClick = {
                startText = ""
                endText = ""
                onStateChange(GalleryFilterState())
            },
            modifier = Modifier.widthIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.s_clear_filters))
        }
    }
}

private fun parseStartDate(text: String): Long? {
    if (text.isBlank()) return null
    return try {
        LocalDate.parse(text.trim(), isoDateFormatter).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

private fun parseEndDate(text: String): Long? {
    if (text.isBlank()) return null
    return try {
        LocalDate.parse(text.trim(), isoDateFormatter).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

private fun makeDateRange(start: Long?, end: Long?): GalleryDateRange? =
    if (start == null && end == null) null else GalleryDateRange(start, end)

private fun formatIsoDate(epochSeconds: Long): String = try {
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate().format(isoDateFormatter)
} catch (_: DateTimeException) {
    ""
}
