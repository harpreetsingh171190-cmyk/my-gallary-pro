package com.mygallery.app.pro

import androidx.compose.ui.res.stringResource
import com.mygallery.app.R

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ScanPage(
    val uri: Uri,
    val label: String,
    val bitmap: Bitmap,
    val rotation: Int = 0,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val topLeftX: Float = 0f,
    val topLeftY: Float = 0f,
    val topRightX: Float = 0f,
    val topRightY: Float = 0f,
    val bottomRightX: Float = 0f,
    val bottomRightY: Float = 0f,
    val bottomLeftX: Float = 0f,
    val bottomLeftY: Float = 0f,
    val ocrText: String? = null,
    val ocrLoading: Boolean = false
)

private const val MAX_SCAN_PAGES = 20

/**
 * Interactive document-page assembly screen.
 *
 * Import/capture pages, arrange them with the move controls, apply crop, manual corner perspective
 * correction and quarter-turn rotation, then export the adjusted pages as one PDF through Android's SAF.
 * The caller may provide [onExported] to react after a successful PDF write.
 */
@Composable
fun DocumentScannerScreen(onExported: (Uri) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf(emptyList<ScanPage>()) }
    var loadingCount by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var textQuery by remember { mutableStateOf("") }

    fun addImages(uris: List<Uri>) {
        val distinctNew = uris.filter { candidate -> pages.none { it.uri == candidate } }
        val available = (MAX_SCAN_PAGES - pages.size - loadingCount).coerceAtLeast(0)
        val additions = distinctNew.take(available)
        if (additions.size < distinctNew.size) {
            Toast.makeText(context, "A document can contain up to $MAX_SCAN_PAGES pages.", Toast.LENGTH_LONG).show()
        }
        if (additions.isEmpty()) return
        loadingCount += additions.size
        scope.launch {
            additions.forEach { uri ->
                val loaded = withContext(Dispatchers.IO) { decodeScanPage(context, uri) }
                if (loaded != null) {
                    pages = pages + loaded
                } else {
                    Toast.makeText(context, "Could not open an image page", Toast.LENGTH_SHORT).show()
                }
                loadingCount = (loadingCount - 1).coerceAtLeast(0)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        addImages(selected)
    }
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCapture
        pendingCapture = null
        if (success && uri != null) addImages(listOf(uri))
        else if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
    }
    val automaticScanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scanning = false
        if (result.resultCode == Activity.RESULT_OK) {
            val imageUris = runCatching {
                GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                    ?.pages?.map { it.imageUri }.orEmpty()
            }.getOrElse { emptyList() }
            if (imageUris.isNotEmpty()) addImages(imageUris)
            else Toast.makeText(context, "The scanner did not return any pages", Toast.LENGTH_LONG).show()
        } else if (result.resultCode != Activity.RESULT_CANCELED) {
            Toast.makeText(context, "Document scan could not be completed", Toast.LENGTH_LONG).show()
        }
    }
    val pdfCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        if (pages.isEmpty()) {
            Toast.makeText(context, "Add at least one page first", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val snapshot = pages.toList()
        exporting = true
        scope.launch {
            val succeeded = withContext(Dispatchers.IO) {
                DocumentStudio.exportPdf(
                    context = context,
                    pageCount = snapshot.size,
                    destination = destination,
                    bitmapAt = { index ->
                        val page = snapshot[index]
                        transformScanPage(page.bitmap, page)
                    },
                    releaseBitmap = { bitmap ->
                        if (snapshot.none { it.bitmap === bitmap }) bitmap.recycle()
                    }
                )
            }
            exporting = false
            if (succeeded) {
                Toast.makeText(context, "PDF saved", Toast.LENGTH_SHORT).show()
                onExported(destination)
            } else {
                Toast.makeText(context, "PDF export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.s_document_scanner), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.s_scan_pages_with_automatic_edge_detection_or_use_the_manual_image_and_camera_options_review_and_adjust_crop_perspective_and_rotation_before_export), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val activity = context.findScannerHostActivity()
                if (activity == null) {
                    Toast.makeText(context, "Automatic scanning needs an Activity host; use Add images or Capture page instead.", Toast.LENGTH_LONG).show()
                } else {
                    scanning = true
                    val options = GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(true)
                        .setPageLimit((MAX_SCAN_PAGES - pages.size).coerceAtLeast(1))
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                        .build()
                    GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                        .addOnSuccessListener { sender ->
                            runCatching {
                                automaticScanner.launch(IntentSenderRequest.Builder(sender).build())
                            }.onFailure {
                                scanning = false
                                Toast.makeText(context, "Could not open automatic scanner. Use the manual options below.", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            scanning = false
                            Toast.makeText(context, "Automatic scanner unavailable. Check Google Play services and your connection, or use the manual options below.", Toast.LENGTH_LONG).show()
                        }
                }
            }, enabled = !exporting && !scanning && loadingCount == 0 && pages.size < MAX_SCAN_PAGES) {
                Text(if (scanning) "Opening scanner…" else "Auto-scan")
            }
            Button(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = !exporting && !scanning && loadingCount == 0 && pages.size < MAX_SCAN_PAGES) {
                Text(stringResource(R.string.s_add_images))
            }
            OutlinedButton(onClick = {
                val captureUri = createCaptureUri(context)
                if (captureUri == null) {
                    Toast.makeText(context, "Could not prepare camera capture", Toast.LENGTH_SHORT).show()
                } else {
                    pendingCapture = captureUri
                    runCatching { cameraCapture.launch(captureUri) }.onFailure {
                        pendingCapture = null
                        runCatching { context.contentResolver.delete(captureUri, null, null) }
                        Toast.makeText(context, "No camera app could capture an image", Toast.LENGTH_LONG).show()
                    }
                }
            }, enabled = !exporting && !scanning && loadingCount == 0 && pages.size < MAX_SCAN_PAGES) {
                Text(stringResource(R.string.s_capture_page))
            }
        }
        Text(stringResource(R.string.s_auto_scan_requires_google_play_services_installed_and_up_to_date_its_scanner_module_may_need_an_internet_connection_to_download_on_first_use_this_screen_does_not_request_runtime_camera_or_broad_photo_library_permission_imports_use_the_system_picker_while_camera_capture_and_automatic_scanning_use_external_system_or_google_ui_if_automatic_scanning_is_unavailable_use_the_manual_options), style = MaterialTheme.typography.bodySmall)
        Text("Pages: ${pages.size} / $MAX_SCAN_PAGES", style = MaterialTheme.typography.labelMedium)
        if (loadingCount > 0) Text("Loading $loadingCount page(s)…", style = MaterialTheme.typography.bodyMedium)
        if (pages.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.s_page_text_ocr), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.s_recognize_text_from_each_adjusted_page_then_search_or_copy_it_here_text_is_stored_for_this_screen_session_and_is_not_embedded_in_the_raster_pdf_bundled_ocr_recognizes_latin_script_text_no_network_or_extra_permission_is_needed_for_ocr), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = textQuery,
                onValueChange = { textQuery = it },
                label = { Text(stringResource(R.string.s_search_recognized_page_text)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            val query = textQuery.trim()
            val matchingPages = pages.filter { page ->
                page.ocrText?.let { recognized -> recognized.isNotBlank() && (query.isEmpty() || recognized.contains(query, ignoreCase = true)) } == true
            }
            val clipboard = LocalClipboardManager.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val text = matchingPages.joinToString("\n\n") { it.ocrText.orEmpty() }
                    if (text.isNotBlank()) clipboard.setText(AnnotatedString(text))
                    else Toast.makeText(context, "No matching recognized text to copy", Toast.LENGTH_SHORT).show()
                }, enabled = matchingPages.isNotEmpty()) {
                    Text("Copy ${if (query.isEmpty()) "all" else "matching"} text")
                }
                Text("${matchingPages.size} page(s) with matching text", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
            }
            if (matchingPages.isNotEmpty()) {
                matchingPages.forEach { page ->
                    Text("${page.label}: ${page.ocrText.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
            } else if (pages.none { !it.ocrText.isNullOrBlank() }) {
                Text(stringResource(R.string.s_no_ocr_text_yet_select_recognize_text_on_a_page_below), style = MaterialTheme.typography.bodySmall)
            } else if (query.isNotEmpty()) {
                Text("No recognized page text matches ‘$query’.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (pages.isEmpty() && loadingCount == 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(stringResource(R.string.s_no_pages_yet_add_one_or_more_images_or_capture_a_page), Modifier.padding(16.dp))
            }
        }

        pages.forEachIndexed { index, page ->
            ScanPageCard(
                page = page,
                index = index,
                total = pages.size,
                onPageChange = { updated ->
                    pages = pages.mapIndexed { i, old ->
                        if (i != index) old
                        else if (!sameScanTransform(old, updated)) updated.copy(ocrText = null, ocrLoading = false)
                        else updated
                    }
                },
                onRecognizeText = {
                    val sourcePage = page
                    pages = pages.mapIndexed { i, old -> if (i == index) old.copy(ocrLoading = true) else old }
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            val prepared = try {
                                transformScanPage(sourcePage.bitmap, sourcePage)
                            } catch (error: Exception) {
                                return@withContext Result.failure<String>(error)
                            }
                            try {
                                Result.success(recognizeScanText(prepared))
                            } catch (error: Exception) {
                                Result.failure(error)
                            } finally {
                                if (prepared !== sourcePage.bitmap) prepared.recycle()
                            }
                        }
                        pages = pages.mapIndexed { i, old ->
                            if (old.bitmap === sourcePage.bitmap && sameScanTransform(old, sourcePage)) old.copy(
                                ocrLoading = false,
                                ocrText = outcome.getOrNull()
                            ) else old
                        }
                        if (outcome.isFailure) Toast.makeText(context, "Could not recognize this page. Try again or use a clearer image.", Toast.LENGTH_LONG).show()
                    }
                },
                onMove = { delta ->
                    val destination = index + delta
                    if (destination in pages.indices) {
                        val reordered = pages.toMutableList()
                        val moved = reordered.removeAt(index)
                        reordered.add(destination, moved)
                        pages = reordered
                    }
                },
                onRemove = { pages = pages.filterIndexed { i, _ -> i != index } }
            )
        }
        if (pages.isNotEmpty()) {
            HorizontalDivider()
            Button(
                onClick = { pdfCreator.launch("scanned_document.pdf") },
                enabled = !exporting && !scanning && loadingCount == 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (exporting) "Creating PDF…" else "Export ${pages.size} page(s) as PDF")
            }
            Text(stringResource(R.string.s_export_preserves_page_order_the_pdf_contains_page_images_recognized_text_remains_searchable_and_copyable_in_the_page_text_section_above_not_embedded_in_the_pdf), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ScanPageCard(
    page: ScanPage,
    index: Int,
    total: Int,
    onPageChange: (ScanPage) -> Unit,
    onRecognizeText: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val preview by produceState<Bitmap?>(
        initialValue = null, page.bitmap, page.rotation, page.left, page.top, page.right, page.bottom,
        page.topLeftX, page.topLeftY, page.topRightX, page.topRightY,
        page.bottomRightX, page.bottomRightY, page.bottomLeftX, page.bottomLeftY
    ) {
        value = withContext(Dispatchers.IO) {
            val smaller = downscaleForPreview(page.bitmap, 1400)
            transformScanPage(smaller, page)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Page ${index + 1} of $total", style = MaterialTheme.typography.titleMedium)
                    Text(page.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onMove(-1) }, enabled = index > 0) { Text(stringResource(R.string.s_up)) }
                    OutlinedButton(onClick = { onMove(1) }, enabled = index < total - 1) { Text(stringResource(R.string.s_down)) }
                    OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.s_remove)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRecognizeText, enabled = !page.ocrLoading) {
                    Text(if (page.ocrLoading) "Recognizing…" else if (page.ocrText == null) "Recognize text" else "Re-run OCR")
                }
                page.ocrText?.let { text ->
                    val clipboard = LocalClipboardManager.current
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.s_copy_page_text)) }
                }
            }
            page.ocrText?.let { text ->
                Text(stringResource(R.string.s_recognized_text_preview), style = MaterialTheme.typography.labelMedium)
                Text(text.ifBlank { "No text was detected on this page." }, style = MaterialTheme.typography.bodySmall, maxLines = 5)
            }
            val image = preview
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Preview of ${page.label}, page ${index + 1}",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Spacer(Modifier.fillMaxWidth().height(220.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Rotation: ${page.rotation}°", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { onPageChange(page.copy(rotation = (page.rotation + 90) % 360)) }) {
                    Text(stringResource(R.string.s_rotate_90))
                }
            }
            CropControl("Left crop", page.left) { onPageChange(page.copy(left = it.coerceAtMost(0.9f - page.right))) }
            CropControl("Top crop", page.top) { onPageChange(page.copy(top = it.coerceAtMost(0.9f - page.bottom))) }
            CropControl("Right crop", page.right) { onPageChange(page.copy(right = it.coerceAtMost(0.9f - page.left))) }
            CropControl("Bottom crop", page.bottom) { onPageChange(page.copy(bottom = it.coerceAtMost(0.9f - page.top))) }
            HorizontalDivider()
            Text(stringResource(R.string.s_perspective_correction), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.s_fine_tune_each_corner_when_the_page_is_skewed_in_the_photo), style = MaterialTheme.typography.bodySmall)
            PerspectiveControl("Top-left X", page.topLeftX, 0f, 0.2f) { onPageChange(page.copy(topLeftX = it)) }
            PerspectiveControl("Top-left Y", page.topLeftY, 0f, 0.2f) { onPageChange(page.copy(topLeftY = it)) }
            PerspectiveControl("Top-right X", page.topRightX, -0.2f, 0f) { onPageChange(page.copy(topRightX = it)) }
            PerspectiveControl("Top-right Y", page.topRightY, 0f, 0.2f) { onPageChange(page.copy(topRightY = it)) }
            PerspectiveControl("Bottom-right X", page.bottomRightX, -0.2f, 0f) { onPageChange(page.copy(bottomRightX = it)) }
            PerspectiveControl("Bottom-right Y", page.bottomRightY, -0.2f, 0f) { onPageChange(page.copy(bottomRightY = it)) }
            PerspectiveControl("Bottom-left X", page.bottomLeftX, 0f, 0.2f) { onPageChange(page.copy(bottomLeftX = it)) }
            PerspectiveControl("Bottom-left Y", page.bottomLeftY, -0.2f, 0f) { onPageChange(page.copy(bottomLeftY = it)) }
        }
    }
}

@Composable
private fun CropControl(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..0.4f)
    }
}

@Composable
private fun PerspectiveControl(label: String, value: Float, minimum: Float, maximum: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = minimum..maximum)
    }
}

private fun decodeScanPage(context: Context, uri: Uri): ScanPage? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1400) inSampleSize *= 2
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
        ?: return null
    val label = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Image page"
    ScanPage(uri = uri, label = label, bitmap = bitmap)
}.getOrNull()

private fun createCaptureUri(context: Context): Uri? = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "scan_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    }
    context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
}.getOrNull()

private fun downscaleForPreview(bitmap: Bitmap, maximumDimension: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maximumDimension) return bitmap
    val factor = maximumDimension.toFloat() / longest
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
}

private fun sameScanTransform(first: ScanPage, second: ScanPage): Boolean =
    first.rotation == second.rotation &&
        first.left == second.left && first.top == second.top &&
        first.right == second.right && first.bottom == second.bottom &&
        first.topLeftX == second.topLeftX && first.topLeftY == second.topLeftY &&
        first.topRightX == second.topRightX && first.topRightY == second.topRightY &&
        first.bottomRightX == second.bottomRightX && first.bottomRightY == second.bottomRightY &&
        first.bottomLeftX == second.bottomLeftX && first.bottomLeftY == second.bottomLeftY

/** Crops normalized insets, optionally maps four manually-adjusted corners to a rectangle, and rotates. */
private fun transformScanPage(bitmap: Bitmap, page: ScanPage): Bitmap {
    val leftN = page.left.coerceIn(0f, 0.4f)
    val topN = page.top.coerceIn(0f, 0.4f)
    val rightN = (1f - page.right.coerceIn(0f, 0.4f)).coerceAtLeast(leftN + 0.1f)
    val bottomN = (1f - page.bottom.coerceIn(0f, 0.4f)).coerceAtLeast(topN + 0.1f)
    val left = (bitmap.width * leftN).toInt().coerceIn(0, bitmap.width - 1)
    val top = (bitmap.height * topN).toInt().coerceIn(0, bitmap.height - 1)
    val right = (bitmap.width * rightN).toInt().coerceIn(left + 1, bitmap.width)
    val bottom = (bitmap.height * bottomN).toInt().coerceIn(top + 1, bitmap.height)
    val outWidth = right - left
    val outHeight = bottom - top
    val offsets = floatArrayOf(page.topLeftX, page.topLeftY, page.topRightX, page.topRightY,
        page.bottomRightX, page.bottomRightY, page.bottomLeftX, page.bottomLeftY)
    val hasPerspective = offsets.any { kotlin.math.abs(it) > 0.001f }
    val cropped = if (!hasPerspective && left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height) bitmap
        else if (!hasPerspective) Bitmap.createBitmap(bitmap, left, top, outWidth, outHeight)
        else {
            val spanX = rightN - leftN
            val spanY = bottomN - topN
            fun corner(x: Float, y: Float): Pair<Float, Float> =
                ((leftN + x * spanX).coerceIn(leftN, rightN) * bitmap.width) to
                    ((topN + y * spanY).coerceIn(topN, bottomN) * bitmap.height)
            val tl = corner(page.topLeftX, page.topLeftY)
            val tr = corner(1f + page.topRightX, page.topRightY)
            val br = corner(1f + page.bottomRightX, 1f + page.bottomRightY)
            val bl = corner(page.bottomLeftX, 1f + page.bottomLeftY)
            val source = floatArrayOf(tl.first, tl.second, tr.first, tr.second, br.first, br.second, bl.first, bl.second)
            val destination = floatArrayOf(0f, 0f, outWidth.toFloat(), 0f, outWidth.toFloat(), outHeight.toFloat(), 0f, outHeight.toFloat())
            val transform = Matrix()
            if (transform.setPolyToPoly(source, 0, destination, 0, 4)) {
                Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { result ->
                    Canvas(result).drawBitmap(bitmap, transform, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
            } else if (left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height) bitmap
            else Bitmap.createBitmap(bitmap, left, top, outWidth, outHeight)
        }
    if (page.rotation == 0) return cropped
    val matrix = Matrix().apply { postRotate(page.rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
    if (cropped !== bitmap) cropped.recycle()
    return rotated
}
