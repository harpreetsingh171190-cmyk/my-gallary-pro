package com.mygallery.app.pro

import androidx.compose.ui.res.stringResource
import com.mygallery.app.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings backup and restore UI. It can be placed in a parent navigation destination directly.
 * SAF import/export uses Android's document picker; WebDAV credentials remain transient in memory
 * and remote upload or restore requires an explicit confirmation.
 */
@Composable
fun BackupScreen(onOperationFinished: (BackupOperationResult) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<BackupOperationResult?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var selectedRestore by remember { mutableStateOf<Uri?>(null) }

    fun perform(label: String, operation: suspend () -> BackupOperationResult) {
        if (busy) return
        busy = true
        status = BackupOperationResult(true, label)
        scope.launch {
            val result = withContext(Dispatchers.IO) { operation() }
            busy = false
            status = result
            if (label.startsWith("Saving backup") || label.startsWith("Uploading backup")) {
                BackupHistory.record(context, result)
            }
            onOperationFinished(result)
        }
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) perform("Saving backup…") { BackupSync.backupSettingsDetailed(context, uri) }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectedRestore = uri
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.s_settings_backup), style = MaterialTheme.typography.headlineSmall)
        Text(
            "Back up app settings and custom album definitions. Media files, favorites, and device-bound vault keys are not included.",
            style = MaterialTheme.typography.bodyMedium
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.s_on_this_device), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { createBackup.launch("my_gallery_settings.zip") }, enabled = !busy) {
                        Text(stringResource(R.string.s_save_backup))
                    }
                    OutlinedButton(onClick = { openBackup.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) {
                        Text(stringResource(R.string.s_restore_backup))
                    }
                }
                Text(stringResource(R.string.s_restore_validates_the_archive_and_replaces_only_the_app_s_allowlisted_settings_preferences), style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.s_webdav_nextcloud), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.s_enter_the_complete_url_for_the_remote_zip_file_for_example_a_nextcloud_webdav_file_path_upload_may_replace_that_file), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.s_webdav_file_url)) },
                    singleLine = true,
                    enabled = !busy
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.s_username_optional)) },
                    singleLine = true,
                    enabled = !busy
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.s_password_optional)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy
                )
                Text(stringResource(R.string.s_use_an_https_file_url_credentials_are_used_only_for_the_request_and_are_not_saved), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { confirmation = "upload" },
                        enabled = !busy && endpoint.isNotBlank()
                    ) { Text(stringResource(R.string.s_upload)) }
                    OutlinedButton(
                        onClick = { confirmation = "download" },
                        enabled = !busy && endpoint.isNotBlank()
                    ) { Text(stringResource(R.string.s_download_restore)) }
                }
            }
        }

        MediaBackupControls()

        BackupHistoryLine(status)
        val result = status
        if (busy) Text(stringResource(R.string.s_working), style = MaterialTheme.typography.bodyMedium)
        if (result != null && !busy) {
            Text(
                result.message,
                color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    val pendingConfirmation = confirmation
    if (pendingConfirmation != null) {
        val isUpload = pendingConfirmation == "upload"
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (isUpload) "Upload settings backup?" else "Download and restore settings?") },
            text = {
                Text(
                    if (isUpload) "This will send the app's allowlisted settings to the supplied server URL and may replace the remote file. Continue?"
                    else "This will retrieve the remote backup, validate it, and replace the app's allowlisted settings. Continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    val url = endpoint.trim()
                    val user = username
                    val secret = password
                    if (isUpload) {
                        perform("Uploading backup…") { BackupSync.uploadSettings(context, url, user, secret) }
                    } else {
                        perform("Downloading and restoring…") { BackupSync.downloadAndRestoreSettings(context, url, user, secret) }
                    }
                }, enabled = !busy) { Text(if (isUpload) "Upload" else "Download & restore") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.s_cancel)) } }
        )
    }

    val restoreUri = selectedRestore
    if (restoreUri != null) {
        AlertDialog(
            onDismissRequest = { selectedRestore = null },
            title = { Text(stringResource(R.string.s_restore_this_backup)) },
            text = { Text(stringResource(R.string.s_the_archive_will_be_validated_first_then_the_app_s_allowlisted_settings_will_be_replaced_no_archive_paths_are_extracted_to_the_filesystem)) },
            confirmButton = {
                TextButton(onClick = {
                    selectedRestore = null
                    perform("Restoring backup…") { BackupSync.restoreSettings(context, restoreUri) }
                }, enabled = !busy) { Text(stringResource(R.string.s_restore)) }
            },
            dismissButton = { TextButton(onClick = { selectedRestore = null }) { Text(stringResource(R.string.s_cancel)) } }
        )
    }
}
