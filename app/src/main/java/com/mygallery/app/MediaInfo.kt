package com.mygallery.app

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class MediaInfo(val rows: List<Pair<String, String>>, val latLong: DoubleArray?)

private fun openExif(context: Context, item: GalleryMedia): ExifInterface? {
    val located = Build.VERSION.SDK_INT >= 29 &&
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    // With location permission, ask for the unredacted original so GPS tags are present.
    if (located) {
        runCatching {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri))?.use { return ExifInterface(it) }
        }
    }
    return runCatching { context.contentResolver.openInputStream(item.uri)?.use { ExifInterface(it) } }.getOrNull()
}

fun loadMediaInfo(context: Context, item: GalleryMedia): MediaInfo {
    val rows = mutableListOf<Pair<String, String>>()
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    var takenMs = 0L; var addedS = 0L; var modifiedS = 0L; var width = 0; var height = 0; var durationMs = 0L; var path: String? = null
    runCatching {
        val columns = mutableListOf(
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED
        )
        if (item.isVideo) columns += MediaStore.MediaColumns.DURATION
        context.contentResolver.query(item.uri, columns.toTypedArray(), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                path = c.getString(0); width = c.getInt(1); height = c.getInt(2)
                takenMs = c.getLong(3); addedS = c.getLong(4); modifiedS = c.getLong(5)
                if (item.isVideo) durationMs = c.getLong(6)
            }
        }
    }
    val exif = if (item.isVideo) null else openExif(context, item)
    if (width <= 0 || height <= 0) {
        width = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
        height = exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0
    }

    rows += "Name" to item.name
    val whenMs = if (takenMs > 0) takenMs else if (addedS > 0) addedS * 1000 else item.date * 1000
    rows += (if (takenMs > 0) "Taken" else "Added") to dateFormat.format(Date(whenMs))
    if (width > 0 && height > 0) {
        val mp = width.toLong() * height / 1_000_000.0
        rows += "Resolution" to ("$width × $height" + if (!item.isVideo && mp >= 0.1) "  (${"%.1f".format(mp)} MP)" else "")
    }
    rows += "Size" to Formatter.formatFileSize(context, item.size)
    if (item.isVideo && durationMs > 0) {
        val total = durationMs / 1000
        rows += "Duration" to if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
    }
    rows += "Type" to item.mime
    rows += "Location on device" to ((path ?: "") + item.name)
    if (modifiedS > 0) rows += "Modified" to dateFormat.format(Date(modifiedS * 1000))

    var latLong: DoubleArray? = null
    if (exif != null) {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
        if (camera.isNotEmpty()) rows += "Camera" to camera
        val f = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        if (f > 0) rows += "Aperture" to "f/${"%.1f".format(f)}"
        val t = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        if (t > 0) rows += "Exposure" to if (t < 1) "1/${(1 / t).roundToInt()} s" else "${"%.1f".format(t)} s"
        exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { rows += "ISO" to it }
        val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        if (focal > 0) rows += "Focal length" to "${"%.1f".format(focal)} mm"
        latLong = exif.latLong
    }
    return MediaInfo(rows, latLong)
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(item: GalleryMedia, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val info by produceState<MediaInfo?>(null, item.uri, reload) { value = withContext(Dispatchers.IO) { loadMediaInfo(context, item) } }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload++ }
    val needsLocation = !item.isVideo && reload >= 0 &&
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.s_details), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            val data = info
            if (data == null) CircularProgressIndicator() else {
                data.rows.forEach { (k, v) -> if (v.isNotBlank()) InfoLine(k, v) }
                data.latLong?.let { ll ->
                    InfoLine("GPS", "%.5f, %.5f".format(ll[0], ll[1]))
                    TextButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${ll[0]},${ll[1]}?q=${ll[0]},${ll[1]}"))) }
                        catch (_: Exception) { Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show() }
                    }) { Text(stringResource(R.string.s_open_in_maps)) }
                }
            }
            if (needsLocation) TextButton(onClick = { locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }) {
                Text(stringResource(R.string.s_allow_location_access_to_show_gps))
            }
        }
    }
}
