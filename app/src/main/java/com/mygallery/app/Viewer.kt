package com.mygallery.app

import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Decodes a screen-sized bitmap (EXIF rotation handled by ImageDecoder). */
internal fun decodeForViewer(context: Context, uri: Uri, maxEdge: Int = 2560): Bitmap? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > maxEdge) decoder.setTargetSampleSize((longest / maxEdge).coerceAtLeast(1))
    }
} catch (_: Throwable) { null }

private fun clampOffset(offset: Offset, scale: Float, width: Float, height: Float): Offset {
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

@Composable
fun FullScreenViewer(
    items: List<GalleryMedia>,
    startIndex: Int,
    favoriteUris: Set<String>,
    onToggleFavorite: (GalleryMedia) -> Unit,
    onShare: (GalleryMedia) -> Unit,
    onEdit: (GalleryMedia) -> Unit,
    onTrim: (GalleryMedia) -> Unit,
    onTrash: (GalleryMedia) -> Unit,
    onClose: () -> Unit
) {
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    // The app theme is light (dark status-bar icons); the viewer is black, so switch to light icons while open.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (prevStatus != null) controller?.isAppearanceLightStatusBars = prevStatus
            if (prevNav != null) controller?.isAppearanceLightNavigationBars = prevNav
        }
    }

    val current = items[pagerState.currentPage.coerceIn(0, items.lastIndex)]
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pagerState, Modifier.fillMaxSize(), key = { items[it].uri.toString() }) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item, isCurrent = pagerState.currentPage == page)
            else ZoomableImage(item, onTap = { chrome = !chrome })
        }
        if (chrome) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.s_back), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Text("${pagerState.currentPage + 1} / ${items.size}", color = Color(0xFFCCCCCC), style = MaterialTheme.typography.labelSmall)
                }
                val isFavorite = current.uri.toString() in favoriteUris
                IconButton(onClick = { onToggleFavorite(current) }, Modifier.size(42.dp)) {
                    Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = stringResource(R.string.s_favorite), tint = if (isFavorite) Color(0xFFFF6B81) else Color.White)
                }
                IconButton(onClick = { onShare(current) }, Modifier.size(42.dp)) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.s_share), tint = Color.White) }
                if (current.isVideo) TextButton(onClick = { onTrim(current) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.s_trim), color = Color.White) }
                else IconButton(onClick = { onEdit(current) }, Modifier.size(42.dp)) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.s_edit), tint = Color.White) }
                IconButton(onClick = { showInfo = true }, Modifier.size(42.dp)) { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.s_details), tint = Color.White) }
                IconButton(onClick = { onTrash(current) }, Modifier.size(42.dp)) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.s_move_to_trash), tint = Color.White) }
            }
        }
        if (showInfo) InfoSheet(current) { showInfo = false }
    }
}

@Composable
private fun ZoomableImage(item: GalleryMedia, onTap: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.uri) { mutableStateOf(false) }
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(item.uri) {
        // Show a small thumbnail instantly, then swap in the sharp version.
        bitmap = withContext(Dispatchers.IO) { try { context.contentResolver.loadThumbnail(item.uri, android.util.Size(512, 512), null) } catch (_: Exception) { null } }
        val full = withContext(Dispatchers.IO) { decodeForViewer(context, item.uri) }
        if (full != null) bitmap = full else if (bitmap == null) failed = true
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f }
                )
            }
            .pointerInput(Unit) {
                // Only consumes touches while zoomed or pinching, so one-finger swipes at 1x still turn the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale > 1f) clampOffset(offset + event.calculatePan(), newScale, size.width.toFloat(), size.height.toFloat()) else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), item.name,
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit
            )
        } else if (failed) Text(stringResource(R.string.s_could_not_open_this_image), color = Color.White)
        else CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun VideoPage(item: GalleryMedia, isCurrent: Boolean) {
    // Only the visible page owns a player; neighbours show a poster so we never hold several decoders at once.
    if (isCurrent) {
        VideoPlayer(item, Modifier.fillMaxSize().navigationBarsPadding())
    } else {
        val context = LocalContext.current
        var poster by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(item.uri) {
            poster = withContext(Dispatchers.IO) { try { context.contentResolver.loadThumbnail(item.uri, android.util.Size(512, 512), null) } catch (_: Exception) { null } }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            poster?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.s_play_video), tint = Color.White, modifier = Modifier.size(64.dp))
        }
    }
}
