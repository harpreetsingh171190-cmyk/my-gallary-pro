package com.mygallery.app

import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Album management surface for integration into a host screen. [selectedMedia] is the current
 * gallery selection; the host owns selection state and can refresh its gallery in
 * [onOperationComplete]. This composable does not request broad storage access.
 */
@Composable
fun AlbumManagementUi(
    selectedMedia: List<GalleryMedia>,
    onDismiss: () -> Unit,
    onOperationComplete: (success: Boolean, message: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshToken by remember { mutableStateOf(0) }
    val albums by produceState(initialValue = emptyList<CustomAlbum>(), key1 = refreshToken) {
        value = withContext(Dispatchers.IO) { CustomAlbums.list(context) }
    }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var editingAlbum by remember { mutableStateOf<CustomAlbum?>(null) }
    var deletingAlbum by remember { mutableStateOf<CustomAlbum?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingSources by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var followUpConsent by remember { mutableStateOf<android.content.IntentSender?>(null) }
    var pendingApi29Consent by remember { mutableStateOf(false) }
    var pendingCopiedCount by remember { mutableStateOf(0) }
    var pendingFailureCount by remember { mutableStateOf(0) }

    fun finishMove(message: String) {
        pendingSources = emptyList()
        pendingApi29Consent = false
        busy = false
        status = message
        onOperationComplete(pendingFailureCount == 0, message)
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            busy = false
            status = "Originals were kept; system deletion consent was declined. The copied items remain in the album."
            onOperationComplete(false, status!!)
        } else if (pendingApi29Consent) {
            // Android 10 exposes per-item RecoverableSecurityException consent. Retry deletion;
            // another item may require its own system dialog, so repeat until complete.
            scope.launch {
                val deleteResult = withContext(Dispatchers.IO) { deleteSourcesAfterConsent(context, pendingSources) }
                if (deleteResult.consentIntentSender != null) {
                    followUpConsent = deleteResult.consentIntentSender
                } else {
                    finishMove("Moved $pendingCopiedCount item(s) into the album." + if (pendingFailureCount > 0) " $pendingFailureCount item(s) could not be copied and their originals were kept." else "")
                }
            }
        } else {
            finishMove("Moved $pendingCopiedCount item(s) into the album." + if (pendingFailureCount > 0) " $pendingFailureCount item(s) could not be copied and their originals were kept." else "")
        }
    }

    LaunchedEffect(followUpConsent) {
        val sender = followUpConsent ?: return@LaunchedEffect
        followUpConsent = null
        consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    fun runTransfer(album: CustomAlbum, move: Boolean) {
        if (selectedMedia.isEmpty() || busy) return
        busy = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { copyMediaToAlbum(context, selectedMedia, album) }
            pendingCopiedCount = result.copiedItems.size
            pendingFailureCount = result.failureMessages.size
            if (result.copiedItems.isEmpty()) {
                busy = false
                val message = result.failureMessages.joinToString("\n").ifBlank { "No media was copied." }
                status = message
                onOperationComplete(false, message)
                return@launch
            }
            if (!move || result.sourceItems.isEmpty()) {
                busy = false
                val message = buildString {
                    append("Copied ${result.copiedItems.size} item(s) into ${album.name}.")
                    if (result.failureMessages.isNotEmpty()) append(" ${result.failureMessages.size} item(s) failed.")
                }
                status = message
                onOperationComplete(result.failureMessages.isEmpty(), message)
                return@launch
            }

            pendingSources = result.sourceItems
            pendingApi29Consent = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sender = withContext(Dispatchers.IO) { createDeleteConsentIntentSender(context, pendingSources) }
                if (sender == null) {
                    busy = false
                    val message = "Media was copied, but Android did not provide a delete consent request; originals were kept."
                    status = message
                    onOperationComplete(false, message)
                } else consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                // API 29 can require a recoverable per-item consent request. Do not fall back to
                // raw filesystem access or silently skip the system-provided consent UI.
                val deleteResult = withContext(Dispatchers.IO) { deleteSourcesAfterConsent(context, pendingSources) }
                if (deleteResult.consentIntentSender != null) consentLauncher.launch(IntentSenderRequest.Builder(deleteResult.consentIntentSender).build())
                else finishMove("Moved ${deleteResult.deletedCount} item(s) into the album." + if (pendingFailureCount > 0) " $pendingFailureCount item(s) could not be copied and their originals were kept." else "")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.s_custom_albums), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.s_close)) }
        }
        Text("${selectedMedia.size} selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Button(onClick = { showCreate = true }, enabled = !busy) { Text(stringResource(R.string.s_create_album)) }
        Spacer(Modifier.height(8.dp))
        if (albums.isEmpty()) {
            Text(stringResource(R.string.s_no_albums_yet_create_an_album_to_organize_copies_of_your_photos_and_videos), modifier = Modifier.padding(vertical = 16.dp))
        } else {
            LazyColumn(Modifier.weight(1f, fill = true), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(albums, key = { it.id }) { album ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedAlbumId == album.id, onClick = { selectedAlbumId = album.id }, enabled = !busy)
                                Column(Modifier.weight(1f)) {
                                    Text(album.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${album.relativePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { editingAlbum = album }, enabled = !busy) { Text(stringResource(R.string.s_rename)) }
                                TextButton(onClick = { deletingAlbum = album }, enabled = !busy) { Text(stringResource(R.string.s_delete)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 48.dp)) {
                                OutlinedButton(onClick = { runTransfer(album, false) }, enabled = !busy && selectedMedia.isNotEmpty()) { Text(stringResource(R.string.s_copy_here)) }
                                Button(onClick = { runTransfer(album, true) }, enabled = !busy && selectedMedia.isNotEmpty()) { Text(stringResource(R.string.s_move_here)) }
                            }
                        }
                    }
                }
            }
        }
        if (busy) Text(stringResource(R.string.s_working), modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
        status?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
    }

    if (showCreate) AlbumNameDialog(title = "Create album", initialName = "", onDismiss = { showCreate = false }) { name ->
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { CustomAlbums.create(context, name) } }
            result.onSuccess { selectedAlbumId = it.id; refreshToken++; status = "Created ${it.name}." }
                .onFailure { status = it.message ?: "Could not create album." }
            showCreate = false
        }
    }
    editingAlbum?.let { album ->
        AlbumNameDialog(title = "Rename album", initialName = album.name, onDismiss = { editingAlbum = null }) { name ->
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { CustomAlbums.rename(context, album.id, name) } }
                result.onSuccess { refreshToken++; status = "Renamed album to ${it.name}." }
                    .onFailure { status = it.message ?: "Could not rename album." }
                editingAlbum = null
            }
        }
    }
    deletingAlbum?.let { album ->
        AlertDialog(
            onDismissRequest = { deletingAlbum = null },
            title = { Text(stringResource(R.string.s_delete_album)) },
            text = { Text("Remove ‘${album.name}’ from your album list? Its media files will remain on the device.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { CustomAlbums.delete(context, album.id) }
                        if (selectedAlbumId == album.id) selectedAlbumId = null
                        refreshToken++
                        status = if (removed) "Album deleted. Its media files were kept." else "Album no longer exists."
                        deletingAlbum = null
                    }
                }) { Text(stringResource(R.string.s_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingAlbum = null }) { Text(stringResource(R.string.s_cancel)) } }
        )
    }
}

@Composable
private fun AlbumNameDialog(title: String, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.s_album_name)) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.trim().isNotEmpty()) { Text(stringResource(R.string.s_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.s_cancel)) } }
    )
}
