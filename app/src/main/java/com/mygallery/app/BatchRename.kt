package com.mygallery.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

private const val PLACEHOLDER = "{n}"
private const val FORBIDDEN = "/\\:*?\"<>|"

/** A pattern is usable when it has the number placeholder and no characters that are illegal in file names. */
fun isValidPattern(pattern: String): Boolean =
    pattern.isNotBlank() && PLACEHOLDER in pattern && pattern.none { it in FORBIDDEN }

/**
 * Plans new names for [items], ordered by date. The original extension is kept. Nothing is renamed here;
 * the caller applies the plan once Android grants write access.
 */
fun planRenames(items: List<GalleryMedia>, pattern: String, start: Int): List<Pair<GalleryMedia, String>> =
    items.sortedBy { it.date }.mapIndexed { i, item ->
        val base = pattern.replace(PLACEHOLDER, (start + i).toString())
        val ext = item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        item to (base + ext)
    }

@Composable
fun BatchRenameDialog(count: Int, onConfirm: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var pattern by remember { mutableStateOf("Photo {n}") }
    var startText by remember { mutableStateOf("1") }
    val start = startText.toIntOrNull()
    val patternOk = isValidPattern(pattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.s_batch_rename_title)) },
        text = {
            Column {
                Text(stringResource(R.string.s_items_count, count), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.s_name_pattern)) },
                    singleLine = true,
                    isError = !patternOk,
                )
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.s_start_number)) },
                    singleLine = true,
                )
                if (!patternOk) {
                    Text(stringResource(R.string.s_pattern_needs_n), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = patternOk && start != null,
                onClick = { start?.let { onConfirm(pattern, it) } },
            ) { Text(stringResource(R.string.s_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.s_cancel)) } },
    )
}


/** Renames one file. The extension is kept, so the name is edited without the dot-suffix. */
@Composable
fun RenameOneDialog(item: GalleryMedia, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val ext = item.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var base by remember { mutableStateOf(item.name.substringBeforeLast('.')) }
    val ok = base.isNotBlank() && base.none { it in FORBIDDEN }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.s_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    singleLine = true,
                    isError = !ok,
                )
                Text(ext, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onConfirm(base + ext) }) { Text(stringResource(R.string.s_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.s_cancel)) } },
    )
}
