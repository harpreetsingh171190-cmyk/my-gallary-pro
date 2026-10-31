package com.mygallery.app

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Square tile for an encrypted vault item. Decrypted image thumbnails are down-sampled and cached in RAM. */
@Composable
fun VaultTile(file: File, onClick: () -> Unit, selected: Boolean = false, onLongClick: () -> Unit = {}) {
    val context = LocalContext.current
    var bitmap by remember(file.name) { mutableStateOf<Bitmap?>(null) }
    var isVideo by remember(file.name) { mutableStateOf(false) }
    var failed by remember(file.name) { mutableStateOf(false) }
    LaunchedEffect(file.absolutePath) {
        val result = withContext(Dispatchers.IO) {
            val metadata = Vault.metadata(context, file)
            metadata to if (metadata.isVideo) null else Vault.thumbnail(context, file, 320)
        }
        isVideo = result.first.isVideo
        bitmap = result.second
        failed = !isVideo && bitmap == null
    }
    Box(
        Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), contentDescription = stringResource(R.string.s_encrypted_photo), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (isVideo) Text(stringResource(R.string.s_video), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        else Text(if (failed) "?" else "…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isVideo) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.72f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.s_video_2), tint = Color.White, modifier = Modifier.padding(4.dp).size(20.dp))
            }
        }
        if (selected) {
            Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                Text("✓", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

private data class VaultEntry(val file: File, val metadata: Vault.Metadata)
private enum class VaultTypeFilter { ALL, IMAGES, VIDEOS }
private enum class VaultSort { DATE, NAME }

/**
 * Searchable/organizable vault browser. Long-press or use the selection action to select items;
 * deletion is only performed after an explicit count-specific confirmation.
 */
@Composable
fun VaultOrganizer(files: List<File>, onOpen: (File) -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(VaultTypeFilter.ALL) }
    var sort by remember { mutableStateOf(VaultSort.DATE) }
    var ascending by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(files) {
        entries = withContext(Dispatchers.IO) {
            files.mapNotNull { file -> runCatching { VaultEntry(file, Vault.metadata(context, file)) }.getOrNull() }
        }
        selected = selected.intersect(files.map { it.absolutePath }.toSet())
    }
    val shown = entries.asSequence()
        .filter { entry -> entry.metadata.fileName.contains(query.trim(), ignoreCase = true) }
        .filter { entry -> when (filter) {
            VaultTypeFilter.ALL -> true
            VaultTypeFilter.IMAGES -> !entry.metadata.isVideo
            VaultTypeFilter.VIDEOS -> entry.metadata.isVideo
        } }
        .let { sequence ->
            when (sort) {
                VaultSort.DATE -> sequence.sortedWith(compareBy<VaultEntry> { it.file.lastModified() }.let { if (ascending) it else it.reversed() })
                VaultSort.NAME -> sequence.sortedWith(compareBy<VaultEntry> { it.metadata.fileName.lowercase() }.let { if (ascending) it else it.reversed() })
            }
        }.toList()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.s_search_vault)) },
            label = { Text(stringResource(R.string.s_search_by_file_name)) }
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(VaultTypeFilter.ALL to "All", VaultTypeFilter.IMAGES to "Photos", VaultTypeFilter.VIDEOS to "Videos").forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp))
            }
            TextButton(onClick = {
                if (sort == VaultSort.DATE) ascending = !ascending else { sort = VaultSort.DATE; ascending = false }
            }) { Text(if (sort == VaultSort.DATE) if (ascending) "Oldest first" else "Newest first" else "Sort: date") }
            TextButton(onClick = {
                if (sort == VaultSort.NAME) ascending = !ascending else { sort = VaultSort.NAME; ascending = true }
            }) { Text(if (sort == VaultSort.NAME) if (ascending) "Name A–Z" else "Name Z–A" else "Sort: name") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected.isEmpty()) {
                Text(stringResource(R.string.s_long_press_an_item_to_select_multiple), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            } else {
                Text("${selected.size} selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.s_cancel)) }
                TextButton(onClick = { confirmDelete = true }, enabled = !deleting) { Text(stringResource(R.string.s_delete)) }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (files.isEmpty()) Text(stringResource(R.string.s_your_vault_is_empty)) else CircularProgressIndicator()
            }
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.s_no_items_match_this_search_filter)) }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 112.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(6.dp)) {
                items(shown, key = { it.file.absolutePath }) { entry ->
                    val path = entry.file.absolutePath
                    val isSelected = path in selected
                    VaultTile(
                        file = entry.file,
                        selected = isSelected,
                        onClick = {
                            if (selected.isNotEmpty()) selected = if (isSelected) selected - path else selected + path
                            else onOpen(entry.file)
                        },
                        onLongClick = { selected = selected + path }
                    )
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Text("Delete ${selected.size} vault item${if (selected.size == 1) "" else "s"}?") },
        text = { Text(stringResource(R.string.s_this_permanently_removes_the_selected_encrypted_copies_from_the_vault_this_cannot_be_undone_original_media_stored_elsewhere_is_not_affected)) },
        confirmButton = {
            TextButton(enabled = !deleting, onClick = {
                deleting = true
                scope.launch {
                    val count = withContext(Dispatchers.IO) { entries.filter { it.file.absolutePath in selected }.count { Vault.delete(it.file) } }
                    selected = emptySet()
                    confirmDelete = false
                    deleting = false
                    deleteResult = "Deleted $count item${if (count == 1) "" else "s"}"
                    onRefresh()
                }
            }) { Text(if (deleting) "Deleting…" else "Delete permanently") }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text(stringResource(R.string.s_cancel)) } }
    )
    deleteResult?.let { message ->
        LaunchedEffect(message) { kotlinx.coroutines.delay(2400); if (deleteResult == message) deleteResult = null }
        Snackbar(Modifier.padding(12.dp)) { Text(message) }
    }
}

/** Uses authenticated plaintext in app-private cache, bounded to Vault.MAX_PREVIEW_BYTES. */
@Composable
private fun VaultVideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playbackFile by remember(file.absolutePath) { mutableStateOf<File?>(null) }
    var errorMessage by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var isPlaying by remember(file.absolutePath) { mutableStateOf(false) }
    var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(file.absolutePath) {
        val result = withContext(Dispatchers.IO) { runCatching { Vault.prepareVideoPlayback(context, file) } }
        result.onSuccess { playbackFile = it }
            .onFailure { errorMessage = if (file.length() > Vault.MAX_PREVIEW_BYTES) "Preview limit: 512 MB. Export the item to play it in another app." else "Could not authenticate or prepare this video." }
    }

    val temp = playbackFile
    Box(modifier) {
        if (temp != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    SurfaceView(viewContext).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                if (player != null) return
                                try {
                                    val newPlayer = MediaPlayer()
                                    player = newPlayer
                                    newPlayer.setDataSource(temp.absolutePath)
                                    newPlayer.setSurface(holder.surface)
                                    newPlayer.isLooping = false
                                    newPlayer.setOnPreparedListener { it.start(); isPlaying = true }
                                    newPlayer.setOnCompletionListener { isPlaying = false }
                                    newPlayer.setOnErrorListener { _, _, _ ->
                                        errorMessage = "This video format could not be played."
                                        isPlaying = false
                                        true
                                    }
                                    newPlayer.prepareAsync()
                                } catch (_: Exception) {
                                    errorMessage = "Could not play this video."
                                    runCatching { player?.release() }
                                    player = null
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                runCatching { player?.release() }
                                player = null
                                isPlaying = false
                            }
                        })
                    }
                }
            )
            DisposableEffect(file.absolutePath, temp) {
                onDispose {
                    runCatching { player?.release() }
                    player = null
                    // Best effort only: abrupt process termination may leave cache plaintext until startup cleanup.
                    temp.delete()
                    isPlaying = false
                }
            }
            IconButton(
                onClick = {
                    val active = player ?: return@IconButton
                    runCatching {
                        if (active.isPlaying) { active.pause(); isPlaying = false }
                        else { active.start(); isPlaying = true }
                    }.onFailure { errorMessage = "Could not control playback." }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
            ) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) stringResource(R.string.s_pause_video) else stringResource(R.string.s_play_video), tint = Color.White)
            }
            Text(stringResource(R.string.s_private_preview_max_512_mb), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(6.dp))
        }
        errorMessage?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(20.dp)) }
        if (temp == null && errorMessage == null) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
}

/** Full-screen preview with image zoom or bounded private-temp video playback, export, and confirmed delete. */
@Composable
fun VaultPreview(file: File, onClose: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(file.name) { mutableStateOf<Bitmap?>(null) }
    var isVideo by remember(file.name) { mutableStateOf(false) }
    var failed by remember(file.name) { mutableStateOf(false) }
    var scale by remember(file.name) { mutableFloatStateOf(1f) }
    var offset by remember(file.name) { mutableStateOf(Offset.Zero) }
    var confirmDelete by remember(file.name) { mutableStateOf(false) }
    BackHandler { onClose() }
    LaunchedEffect(file.absolutePath) {
        val result = withContext(Dispatchers.IO) {
            val metadata = Vault.metadata(context, file)
            metadata to if (metadata.isVideo) null else Vault.thumbnail(context, file, 2048)
        }
        isVideo = result.first.isVideo
        bitmap = result.second
        failed = !isVideo && bitmap == null
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            VaultVideoPlayer(file, Modifier.fillMaxSize())
        } else {
            val b = bitmap
            if (b != null) {
                Image(
                    b.asImageBitmap(), contentDescription = stringResource(R.string.s_vault_photo),
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(file.name) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit
                )
            } else if (failed) Text(stringResource(R.string.s_could_not_decrypt_or_open_this_item), color = Color.White, modifier = Modifier.align(Alignment.Center))
            else CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        Row(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.s_back), tint = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport) { Text(stringResource(R.string.s_export_copy), color = Color.White) }
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.s_delete), color = Color(0xFFFF8A80)) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.s_delete_from_vault)) },
        text = { Text(stringResource(R.string.s_this_permanently_deletes_the_encrypted_copy_the_original_media_stored_elsewhere_is_not_affected)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.s_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.s_cancel)) } }
    )
}
