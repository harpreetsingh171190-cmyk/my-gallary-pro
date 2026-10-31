package com.mygallery.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource

/** Long-press menu for one photo or video in the main grid. */
@Composable
fun MediaActionsDialog(
    item: GalleryMedia,
    onSelect: () -> Unit,
    onSetAs: () -> Unit,
    onTrash: () -> Unit,
    onDetails: () -> Unit,
    onMap: () -> Unit,
    onSmallerCopy: () -> Unit,
    onRotate: () -> Unit,
    onSetCover: (() -> Unit)? = null,
    onRename: () -> Unit = {},
    onMoveEarlier: (() -> Unit)? = null,
    onMoveLater: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 1) },
        text = {
            Column {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(item.name)); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.s_copy_name)) }
                TextButton(
                    onClick = { shareMedia(context, listOf(item)); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.s_share)) }
                TextButton(
                    onClick = { onTrash(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.s_move_to_trash)) }
                TextButton(
                    onClick = { onDetails(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.s_details)) }
                TextButton(onClick = { onSelect(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.s_select))
                }
                // Wallpaper and contact photos are still images only.
                TextButton(onClick = { onRename(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.s_rename))
                }
                onMoveEarlier?.let { move ->
                    TextButton(onClick = { move(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.s_move_earlier))
                    }
                }
                onMoveLater?.let { move ->
                    TextButton(onClick = { move(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.s_move_later))
                    }
                }
                if (!item.isVideo) {
                    TextButton(onClick = { onMap(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.s_show_on_map))
                    }
                    TextButton(onClick = { onSmallerCopy(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.s_save_smaller_copy))
                    }
                    TextButton(onClick = { onRotate(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.s_save_rotated))
                    }
                    onSetCover?.let { setCover ->
                        TextButton(onClick = { setCover(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.s_set_album_cover))
                        }
                    }
                    TextButton(onClick = { onSetAs(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.s_set_as_wallpaper_contact))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.s_cancel)) } },
    )
}

/**
 * Hands the image to the system "Set as" chooser (wallpaper, contact photo, lock screen, depending
 * on the phone). No storage or wallpaper permission is needed, because the receiving app is the one
 * that writes the image.
 */
fun setAsMedia(context: Context, item: GalleryMedia) {
    val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(item.uri, item.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, context.getString(R.string.s_set_as))
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, context.getString(R.string.s_no_app_for_set_as), Toast.LENGTH_SHORT).show() }
}
