package com.mygallery.app

import androidx.compose.ui.res.stringResource

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.mygallery.app.pro.VideoStudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MIN_CLIP_MS = 500L

private fun fmt(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return "%d:%02d.%d".format(t / 60000, (t / 1000) % 60, (t % 1000) / 100)
}

/** Copies the exported clip into Movies/MyGallery through MediaStore (no storage permission needed). Always deletes [file]. */
private fun saveTrimmedCopy(context: Context, file: File, originalName: String): Boolean {
    val resolver = context.contentResolver
    val base = originalName.substringBeforeLast('.', originalName).take(60)
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_trim_$stamp.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MyGallery")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) { file.delete(); return false }
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("No output stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (_: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        false
    } finally { file.delete() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimScreen(item: GalleryMedia, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(item.uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(item.uri)); prepare(); playWhenReady = false } }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val transformerRef = remember { arrayOfNulls<Transformer>(1) }

    DisposableEffect(player) {
        fun readDuration() {
            if (durationMs == 0L && player.playbackState == Player.STATE_READY && player.duration > 0) {
                durationMs = player.duration; startMs = 0L; endMs = player.duration
            }
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = readDuration()
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        readDuration()
        onDispose { player.removeListener(listener); transformerRef[0]?.cancel(); player.release() }
    }

    // Keeps the position label fresh and stops/loops playback at the selected end point.
    LaunchedEffect(player) {
        while (true) {
            delay(80)
            positionMs = player.currentPosition
            if (player.isPlaying && endMs > 0 && positionMs >= endMs) {
                player.pause(); player.seekTo(startMs); positionMs = startMs
            }
        }
    }
    LaunchedEffect(exporting) {
        val holder = ProgressHolder()
        while (exporting) {
            val t = transformerRef[0]
            if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress / 100f
            delay(300)
        }
    }

    fun startExport() {
        player.pause()
        val out = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        exporting = true; progress = 0f
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { saveTrimmedCopy(context, out, item.name) }
                    exporting = false
                    Toast.makeText(context, if (ok) "Trimmed copy saved to Movies/MyGallery" else "Could not save the trimmed video", Toast.LENGTH_LONG).show()
                    if (ok) onSaved()
                }
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                out.delete(); exporting = false
                Toast.makeText(context, "Trim failed (${exportException.errorCodeName})", Toast.LENGTH_LONG).show()
            }
        }
        transformerRef[0] = try { VideoStudio.trim(context, item.uri, startMs, endMs, out.absolutePath, listener) }
        catch (_: Exception) { out.delete(); exporting = false; Toast.makeText(context, "Could not start trimming", Toast.LENGTH_LONG).show(); null }
    }

    BackHandler { if (!exporting) onClose() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!exporting) onClose() }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.s_close)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.s_trim_video), style = MaterialTheme.typography.titleLarge)
                Text(item.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                enabled = !exporting && durationMs > 0 && endMs - startMs >= MIN_CLIP_MS,
                onClick = { startExport() },
                modifier = Modifier.padding(end = 8.dp)
            ) { Text(stringResource(R.string.s_save_copy)) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            val exo = player
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = exo } }, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (durationMs <= 0) Text(stringResource(R.string.s_loading_video)) else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = !exporting, onClick = {
                        if (playing) player.pause() else {
                            if (player.currentPosition !in startMs until endMs) player.seekTo(startMs)
                            player.play()
                        }
                    }) { Text(if (playing) "Pause" else "Play selection") }
                    Text("${fmt(positionMs)} / ${fmt(durationMs)}")
                }
                RangeSlider(
                    value = startMs.toFloat()..endMs.toFloat(),
                    onValueChange = { r ->
                        val s = r.start.toLong(); val e = r.endInclusive.toLong()
                        if (e - s >= MIN_CLIP_MS) {
                            val startMoved = s != startMs
                            startMs = s; endMs = e
                            player.pause()
                            player.seekTo(if (startMoved) s else (e - 100).coerceAtLeast(s))
                        }
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    enabled = !exporting
                )
                Text("Start ${fmt(startMs)}  •  End ${fmt(endMs)}  •  Length ${fmt(endMs - startMs)}", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !exporting, onClick = {
                        val p = player.currentPosition
                        if (p < endMs - MIN_CLIP_MS) startMs = p
                    }) { Text(stringResource(R.string.s_start_now)) }
                    OutlinedButton(enabled = !exporting, onClick = {
                        val p = player.currentPosition.coerceAtMost(durationMs)
                        if (p > startMs + MIN_CLIP_MS) endMs = p
                    }) { Text(stringResource(R.string.s_end_now)) }
                }
                if (exporting) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Saving trimmed copy… ${(progress * 100).toInt()}%")
                } else Text(stringResource(R.string.s_the_original_video_is_not_changed_a_trimmed_copy_is_saved_to_movies_mygallery), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
