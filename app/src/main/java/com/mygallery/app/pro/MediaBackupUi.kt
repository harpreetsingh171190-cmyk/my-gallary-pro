package com.mygallery.app.pro

import androidx.compose.ui.res.stringResource
import com.mygallery.app.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Separate, explicit media archive flow. Restore always previews the validated archive first. */
@Composable
fun MediaBackupControls(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var inspection by remember { mutableStateOf<MediaBackupInspection?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmUpload by remember { mutableStateOf(false) }
    var confirmRemoteRestore by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val stagedInspection = inspection
    DisposableEffect(stagedInspection) {
        onDispose { stagedInspection?.let { staged -> BackupMedia.discard(staged) } }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        sources = uris.distinct()
        status = null
    }
    val archiveCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
        if (destination != null) {
            val selected = sources.toList()
            scope.launch {
                busy = true
                status = "Creating archive…"
                val result = withContext(Dispatchers.IO) { BackupMedia.createLocal(context, selected, destination) }
                busy = false
                status = result.message
            }
        }
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) {
            scope.launch {
                busy = true
                status = "Validating archive…"
                val (result, staged) = withContext(Dispatchers.IO) { BackupMedia.inspectLocal(context, source) }
                busy = false
                inspection = staged
                selectedIds = staged?.items?.map { it.id }?.toSet().orEmpty()
                status = result.message
            }
        }
    }

    fun launchRemoteUpload() {
        val selected = sources.toList()
        val url = endpoint.trim()
        val user = username
        val secret = password
        scope.launch {
            busy = true
            status = "Uploading selected media…"
            val result = withContext(Dispatchers.IO) { BackupMedia.upload(context, selected, url, user, secret) }
            busy = false
            status = result.message
        }
    }

    fun inspectRemoteArchive() {
        val url = endpoint.trim()
        val user = username
        val secret = password
        scope.launch {
            busy = true
            status = "Downloading and validating archive…"
            val (result, staged) = withContext(Dispatchers.IO) { BackupMedia.inspectRemote(context, url, user, secret) }
            busy = false
            inspection = staged
            selectedIds = staged?.items?.map { it.id }?.toSet().orEmpty()
            status = result.message
        }
    }

    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.s_selected_media_backup), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.s_create_a_separate_archive_of_selected_photos_and_videos_or_preview_an_archive_and_restore_only_chosen_items_restore_adds_copies_and_never_deletes_originals), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.s_limits_1_000_items_1_gb_per_item_and_2_gb_total_large_archives_need_free_device_storage_while_staged), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { mediaPicker.launch(arrayOf("image/*", "video/*")) }, enabled = !busy) { Text(stringResource(R.string.s_choose_media)) }
                Text("${sources.size} selected", style = MaterialTheme.typography.bodyMedium)
            }
            if (sources.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { archiveCreator.launch("my_gallery_media.zip") }, enabled = !busy) { Text(stringResource(R.string.s_save_local_archive)) }
                    TextButton(onClick = { sources = emptyList() }, enabled = !busy) { Text(stringResource(R.string.s_clear)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) { Text(stringResource(R.string.s_open_local_archive)) }
            }
            Text(stringResource(R.string.s_remote_media_archive_https), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.s_webdav_file_url)) }, singleLine = true, enabled = !busy)
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.s_username_optional)) }, singleLine = true, enabled = !busy)
            OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.s_password_optional)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Text(stringResource(R.string.s_use_a_dedicated_https_file_url_for_the_media_archive_credentials_are_used_for_the_request_only_and_are_not_saved), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmUpload = true }, enabled = !busy && sources.isNotEmpty() && endpoint.isNotBlank()) { Text(stringResource(R.string.s_upload_selected)) }
                OutlinedButton(onClick = { confirmRemoteRestore = true }, enabled = !busy && endpoint.isNotBlank()) { Text(stringResource(R.string.s_preview_remote)) }
            }
            status?.let { Text(it, color = if (it.contains("failed", true) || it.contains("could not", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (busy) Text(stringResource(R.string.s_working), style = MaterialTheme.typography.bodySmall)

            inspection?.let { archive ->
                Text("Validated archive: ${archive.items.size} media item(s)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { selectedIds = archive.items.map { it.id }.toSet() }) { Text(stringResource(R.string.s_select_all)) }
                    TextButton(onClick = { selectedIds = emptySet() }) { Text(stringResource(R.string.s_select_none)) }
                    TextButton(onClick = { inspection = null; selectedIds = emptySet() }) { Text(stringResource(R.string.s_close)) }
                }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    archive.items.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = item.id in selectedIds, onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                            })
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                Text("${item.mimeType} · ${formatArchiveBytes(item.size)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Button(onClick = { confirmRestore = true }, enabled = !busy && selectedIds.isNotEmpty()) { Text("Restore ${selectedIds.size} selected copy/copies") }
            }
        }
    }

    if (confirmUpload) AlertDialog(
        onDismissRequest = { confirmUpload = false },
        title = { Text(stringResource(R.string.s_upload_selected_media)) },
        text = { Text("Upload ${sources.size} selected item(s) as an archive to the HTTPS URL. This may replace the remote file. Continue?") },
        confirmButton = { TextButton(onClick = { confirmUpload = false; launchRemoteUpload() }, enabled = !busy) { Text(stringResource(R.string.s_upload)) } },
        dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text(stringResource(R.string.s_cancel)) } }
    )
    if (confirmRemoteRestore) AlertDialog(
        onDismissRequest = { confirmRemoteRestore = false },
        title = { Text(stringResource(R.string.s_download_remote_archive_for_review)) },
        text = { Text(stringResource(R.string.s_the_https_archive_will_be_downloaded_to_private_temporary_storage_and_validated_nothing_is_restored_until_you_review_and_confirm_selected_entries)) },
        confirmButton = { TextButton(onClick = { confirmRemoteRestore = false; inspectRemoteArchive() }, enabled = !busy) { Text(stringResource(R.string.s_download)) } },
        dismissButton = { TextButton(onClick = { confirmRemoteRestore = false }) { Text(stringResource(R.string.s_cancel)) } }
    )
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Restore ${selectedIds.size} media item(s)?") },
        text = { Text(stringResource(R.string.s_selected_media_will_be_added_as_new_copies_under_pictures_mygallery_restored_or_movies_mygallery_restored_existing_originals_will_not_be_changed_or_deleted)) },
        confirmButton = { TextButton(onClick = {
            val current = inspection ?: return@TextButton
            val ids = selectedIds.toSet()
            confirmRestore = false
            scope.launch {
                busy = true
                status = "Restoring selected copies…"
                val result = withContext(Dispatchers.IO) { BackupMedia.restore(context, current, ids) }
                busy = false
                status = result.message
                if (result.success) { inspection = null; selectedIds = emptySet() }
            }
        }, enabled = !busy) { Text(stringResource(R.string.s_restore_copies)) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.s_cancel)) } }
    )
}

private fun formatArchiveBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
