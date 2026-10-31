package com.mygallery.app

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.graphics.Paint as NativePaint
import android.graphics.Path as NativePath
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OverlayTool { NONE, DRAW, ERASE, PLACE_TEXT, PLACE_STICKER, SELECT, SHAPE, BLUR, PIXELATE, FREE_CROP }
private data class EditorColor(val label: String, val value: Int)
private sealed interface OverlaySelection {
    data class Stroke(val index: Int) : OverlaySelection
    data class Shape(val index: Int) : OverlaySelection
    data class Text(val index: Int) : OverlaySelection
    data class Sticker(val index: Int) : OverlaySelection
    data class Effect(val index: Int) : OverlaySelection
}
private data class EditorSnapshot(
    val params: EditParams,
    val strokes: List<EditorStroke>,
    val shapes: List<EditorShape>,
    val texts: List<EditorText>,
    val stickers: List<EditorSticker>,
    val effects: List<EditorRegionEffect>
)

private val editorColors = listOf(
    EditorColor("White", android.graphics.Color.WHITE),
    EditorColor("Yellow", android.graphics.Color.YELLOW),
    EditorColor("Red", android.graphics.Color.RED),
    EditorColor("Blue", android.graphics.Color.CYAN),
    EditorColor("Black", android.graphics.Color.BLACK)
)

private val editorStickers = listOf("★", "♥", "☀", "♪", "☺", "✿", "⚡")

/** Non-destructive photo editor UI. The public composable is the integration entry point. */
@Composable
fun PhotoEditScreen(item: GalleryMedia, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val densityScale = LocalDensity.current.density
    var original by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(item.uri) { mutableStateOf(false) }
    var params by remember(item.uri) { mutableStateOf(EditParams()) }
    var strokes by remember(item.uri) { mutableStateOf(emptyList<EditorStroke>()) }
    var shapes by remember(item.uri) { mutableStateOf(emptyList<EditorShape>()) }
    var activeStroke by remember(item.uri) { mutableStateOf<EditorStroke?>(null) }
    var activeShape by remember(item.uri) { mutableStateOf<EditorShape?>(null) }
    var activeCropRect by remember(item.uri) { mutableStateOf<EditorCropRect?>(null) }
    var activeRegionEffect by remember(item.uri) { mutableStateOf<EditorRegionEffect?>(null) }
    var texts by remember(item.uri) { mutableStateOf(emptyList<EditorText>()) }
    var stickers by remember(item.uri) { mutableStateOf(emptyList<EditorSticker>()) }
    var effects by remember(item.uri) { mutableStateOf(emptyList<EditorRegionEffect>()) }
    var tool by remember(item.uri) { mutableStateOf(OverlayTool.NONE) }
    var selectedOverlay by remember(item.uri) { mutableStateOf<OverlaySelection?>(null) }
    var selectedColor by remember(item.uri) { mutableIntStateOf(android.graphics.Color.WHITE) }
    var brushSize by remember(item.uri) { mutableFloatStateOf(6f) }
    var textSize by remember(item.uri) { mutableFloatStateOf(0.075f) }
    var shapeKind by remember(item.uri) { mutableStateOf(EditorShapeKind.LINE) }
    var selectedFont by remember(item.uri) { mutableStateOf(EditorFont.SANS) }
    var pendingSticker by remember(item.uri) { mutableStateOf("") }
    var displayImageWidthPx by remember(item.uri) { mutableFloatStateOf(1f) }
    var undoHistory by remember(item.uri) { mutableStateOf(emptyList<EditorSnapshot>()) }
    var redoHistory by remember(item.uri) { mutableStateOf(emptyList<EditorSnapshot>()) }
    var sliderStartSnapshot by remember(item.uri) { mutableStateOf<EditorSnapshot?>(null) }
    var textDialog by remember { mutableStateOf(false) }
    var textEntry by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun snapshot() = EditorSnapshot(params, strokes.toList(), shapes.toList(), texts.toList(), stickers.toList(), effects.toList())
    fun recordBefore(before: EditorSnapshot) {
        if (snapshot() != before) {
            undoHistory = (undoHistory + before).takeLast(100)
            redoHistory = emptyList()
        }
    }
    fun rememberCurrent() {
        undoHistory = (undoHistory + snapshot()).takeLast(100)
        redoHistory = emptyList()
    }
    fun beginSliderEdit() {
        if (sliderStartSnapshot == null) sliderStartSnapshot = snapshot()
    }
    fun finishSliderEdit() {
        sliderStartSnapshot?.let { recordBefore(it) }
        sliderStartSnapshot = null
    }
    fun restore(state: EditorSnapshot) {
        params = state.params
        strokes = state.strokes
        shapes = state.shapes
        texts = state.texts
        stickers = state.stickers
        effects = state.effects
        activeStroke = null
        activeShape = null
        activeCropRect = null
        activeRegionEffect = null
        selectedOverlay = null
    }
    fun undo() {
        val previous = undoHistory.lastOrNull() ?: return
        redoHistory = redoHistory + snapshot()
        undoHistory = undoHistory.dropLast(1)
        restore(previous)
    }
    fun redo() {
        val next = redoHistory.lastOrNull() ?: return
        undoHistory = (undoHistory + snapshot()).takeLast(100)
        redoHistory = redoHistory.dropLast(1)
        restore(next)
    }
    fun moveOverlay(selection: OverlaySelection, deltaX: Float, deltaY: Float) {
        when (selection) {
            is OverlaySelection.Stroke -> strokes = strokes.mapIndexed { index, stroke ->
                if (index == selection.index) stroke.copy(points = stroke.points.map { point ->
                    EditorPoint((point.x + deltaX).coerceIn(0f, 1f), (point.y + deltaY).coerceIn(0f, 1f))
                }) else stroke
            }
            is OverlaySelection.Shape -> shapes = shapes.mapIndexed { index, shape ->
                if (index == selection.index) shape.copy(
                    start = EditorPoint((shape.start.x + deltaX).coerceIn(0f, 1f), (shape.start.y + deltaY).coerceIn(0f, 1f)),
                    end = EditorPoint((shape.end.x + deltaX).coerceIn(0f, 1f), (shape.end.y + deltaY).coerceIn(0f, 1f))
                ) else shape
            }
            is OverlaySelection.Text -> texts = texts.mapIndexed { index, text ->
                if (index == selection.index) text.copy(x = (text.x + deltaX).coerceIn(0f, 1f), y = (text.y + deltaY).coerceIn(0f, 1f)) else text
            }
            is OverlaySelection.Sticker -> stickers = stickers.mapIndexed { index, sticker ->
                if (index == selection.index) sticker.copy(x = (sticker.x + deltaX).coerceIn(0f, 1f), y = (sticker.y + deltaY).coerceIn(0f, 1f)) else sticker
            }
            is OverlaySelection.Effect -> effects = effects.mapIndexed { index, effect ->
                if (index == selection.index) { val e = effect.normalized(); val dx = deltaX.coerceIn(-e.start.x, 1f - e.end.x); val dy = deltaY.coerceIn(-e.start.y, 1f - e.end.y); e.copy(start = EditorPoint(e.start.x + dx, e.start.y + dy), end = EditorPoint(e.end.x + dx, e.end.y + dy)) } else effect
            }
        }
    }

    LaunchedEffect(item.uri) {
        val loaded = withContext(Dispatchers.IO) { try { PhotoEditor.load(context, item.uri) } catch (_: Throwable) { null } }
        original = loaded
        loadFailed = loaded == null
    }

    val hasOverlays = strokes.isNotEmpty() || shapes.isNotEmpty() || texts.isNotEmpty() || stickers.isNotEmpty() || effects.isNotEmpty()
    val hasEdits = !params.isDefault || hasOverlays
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Photo Editor — ${item.name}")
        val bitmap = original
        if (bitmap != null) {
            val small = remember(bitmap) { PhotoEditor.downscale(bitmap, 1080) }
            // Preview and full-resolution save both pass through the same transform/crop function.
            val preview = remember(small, params) { PhotoEditor.edit(small, params) }
            val effectPreview = remember(preview, effects) { PhotoEditor.renderEffects(preview, effects) }
            Box(
                Modifier.fillMaxWidth().height(260.dp).onSizeChanged { size ->
                    displayImageWidthPx = fittedImageRect(size.width.toFloat(), size.height.toFloat(), preview.width.toFloat(), preview.height.toFloat()).width
                }
            ) {
                Image(effectPreview.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(tool, preview.width, preview.height) {
                        val rect = fittedImageRect(size.width.toFloat(), size.height.toFloat(), preview.width.toFloat(), preview.height.toFloat())
                        fun normalizedPoint(position: Offset, clamp: Boolean = false): EditorPoint? {
                            if (!clamp && (position.x < rect.left || position.x > rect.right || position.y < rect.top || position.y > rect.bottom)) return null
                            return EditorPoint(((position.x - rect.left) / rect.width).coerceIn(0f, 1f), ((position.y - rect.top) / rect.height).coerceIn(0f, 1f))
                        }
                        fun selectAt(position: Offset): OverlaySelection? {
                            val point = normalizedPoint(position) ?: return null
                            return findOverlayAt(point, rect, strokes, shapes, texts, stickers, effects, 20.dp.toPx())
                        }
                        fun syncSelectedSize(selection: OverlaySelection?) {
                            when (selection) {
                                is OverlaySelection.Stroke -> strokes.getOrNull(selection.index)?.let { stroke ->
                                    brushSize = (stroke.width * rect.width / densityScale).coerceIn(2f, 18f); selectedColor = stroke.color
                                }
                                is OverlaySelection.Shape -> shapes.getOrNull(selection.index)?.let { shape ->
                                    brushSize = (shape.width * rect.width / densityScale).coerceIn(2f, 18f); selectedColor = shape.color
                                }
                                is OverlaySelection.Text -> texts.getOrNull(selection.index)?.let { text ->
                                    textSize = text.size.coerceIn(0.04f, 0.12f); selectedColor = text.color; selectedFont = text.font
                                }
                                is OverlaySelection.Sticker -> stickers.getOrNull(selection.index)?.let { selectedColor = it.color; textSize = it.size.coerceIn(0.04f, 0.20f) }
                                is OverlaySelection.Effect, null -> Unit
                            }
                        }
                        fun eraseAt(position: Offset) {
                            val point = normalizedPoint(position) ?: return
                            val hit = findStrokeAt(point, rect, strokes, 18.dp.toPx()) ?: return
                            strokes = strokes.filterIndexed { index, _ -> index != hit }
                            if (selectedOverlay == OverlaySelection.Stroke(hit)) selectedOverlay = null
                            else if (selectedOverlay is OverlaySelection.Stroke) {
                                val selected = (selectedOverlay as OverlaySelection.Stroke).index
                                selectedOverlay = if (selected > hit) OverlaySelection.Stroke(selected - 1) else selectedOverlay
                            }
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPosition = down.position
                            val before = snapshot()
                            var movedPastSlop = false
                            var drawingStroke: EditorStroke? = null
                            var drawingShape: EditorShape? = null
                            var drawingEffect: EditorRegionEffect? = null
                            var dragSelection: OverlaySelection? = null
                            var cropStart: EditorPoint? = null
                            var lastPosition = startPosition
                            var previousNormalized = normalizedPoint(startPosition, clamp = true)
                            if (tool == OverlayTool.ERASE) eraseAt(startPosition)
                            if (tool == OverlayTool.FREE_CROP) cropStart = normalizedPoint(startPosition)
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                lastPosition = change.position
                                val distance = (change.position - startPosition).getDistance()
                                if (!movedPastSlop && distance > viewConfiguration.touchSlop) {
                                    movedPastSlop = true
                                    when (tool) {
                                        OverlayTool.DRAW -> normalizedPoint(startPosition)?.let { point ->
                                            drawingStroke = EditorStroke(listOf(point), selectedColor, brushSize.dp.toPx() / rect.width)
                                            activeStroke = drawingStroke
                                        }
                                        OverlayTool.SHAPE -> normalizedPoint(startPosition)?.let { point ->
                                            drawingShape = EditorShape(shapeKind, point, point, selectedColor, brushSize.dp.toPx() / rect.width)
                                            activeShape = drawingShape
                                        }
                                        OverlayTool.BLUR, OverlayTool.PIXELATE -> normalizedPoint(startPosition)?.let { point ->
                                            val kind = if (tool == OverlayTool.BLUR) EditorEffectKind.BLUR else EditorEffectKind.PIXELATE
                                            drawingEffect = EditorRegionEffect(kind, point, point); activeRegionEffect = drawingEffect
                                        }
                                        OverlayTool.SELECT -> {
                                            dragSelection = selectAt(startPosition)
                                            selectedOverlay = dragSelection
                                            syncSelectedSize(dragSelection)
                                        }
                                        else -> Unit
                                    }
                                }
                                if (movedPastSlop) {
                                    when (tool) {
                                        OverlayTool.DRAW -> normalizedPoint(change.position, clamp = true)?.let { point ->
                                            drawingStroke?.let { stroke ->
                                                val updated = stroke.copy(points = stroke.points + point)
                                                drawingStroke = updated; activeStroke = updated
                                            }
                                        }
                                        OverlayTool.SHAPE -> normalizedPoint(change.position, clamp = true)?.let { point ->
                                            drawingShape?.let { shape -> val updated = shape.copy(end = point); drawingShape = updated; activeShape = updated }
                                        }
                                        OverlayTool.BLUR, OverlayTool.PIXELATE -> normalizedPoint(change.position, clamp = true)?.let { point ->
                                            drawingEffect?.let { effect -> val updated = effect.copy(end = point); drawingEffect = updated; activeRegionEffect = updated }
                                        }
                                        OverlayTool.SELECT -> {
                                            val currentNormalized = normalizedPoint(change.position, clamp = true)
                                            val previous = previousNormalized
                                            if (dragSelection != null && currentNormalized != null && previous != null)
                                                moveOverlay(dragSelection!!, currentNormalized.x - previous.x, currentNormalized.y - previous.y)
                                            previousNormalized = currentNormalized
                                        }
                                        OverlayTool.ERASE -> eraseAt(change.position)
                                        OverlayTool.FREE_CROP -> {
                                            val start = cropStart
                                            val current = normalizedPoint(change.position, clamp = true)
                                            if (start != null && current != null) activeCropRect = EditorCropRect(start.x, start.y, current.x, current.y).normalized()
                                        }
                                        else -> Unit
                                    }
                                    change.consume()
                                }
                            } while (change.pressed)

                            when (tool) {
                                OverlayTool.PLACE_TEXT -> if (!movedPastSlop) normalizedPoint(startPosition)?.let { point ->
                                    texts = texts + EditorText(pendingText, point.x, point.y, selectedColor, textSize, selectedFont)
                                    selectedOverlay = OverlaySelection.Text(texts.lastIndex)
                                    pendingText = ""; tool = OverlayTool.NONE
                                }
                                OverlayTool.PLACE_STICKER -> if (!movedPastSlop) normalizedPoint(startPosition)?.let { point ->
                                    stickers = stickers + EditorSticker(pendingSticker, point.x, point.y, selectedColor)
                                    selectedOverlay = OverlaySelection.Sticker(stickers.lastIndex); pendingSticker = ""; tool = OverlayTool.NONE
                                }
                                OverlayTool.SELECT -> if (!movedPastSlop) {
                                    val selection = selectAt(startPosition); selectedOverlay = selection; syncSelectedSize(selection)
                                }
                                OverlayTool.SHAPE -> drawingShape?.let { shape ->
                                    shapes = shapes + shape.copy(end = normalizedPoint(lastPosition, clamp = true) ?: shape.end)
                                    selectedOverlay = OverlaySelection.Shape(shapes.lastIndex); activeShape = null
                                }
                                OverlayTool.BLUR, OverlayTool.PIXELATE -> drawingEffect?.let { effect ->
                                    val final = effect.copy(end = normalizedPoint(lastPosition, clamp = true) ?: effect.end).normalized()
                                    if (final.end.x - final.start.x > 0.01f && final.end.y - final.start.y > 0.01f) { effects = effects + final; selectedOverlay = OverlaySelection.Effect(effects.lastIndex) }
                                    activeRegionEffect = null
                                }
                                OverlayTool.FREE_CROP -> {
                                    val crop = activeCropRect
                                    if (crop != null && crop.right - crop.left > 0.025f && crop.bottom - crop.top > 0.025f) {
                                        params = params.copy(crop = CropRatio.ORIGINAL, freeCrop = crop)
                                        tool = OverlayTool.NONE
                                    }
                                    activeCropRect = null
                                }
                                else -> Unit
                            }
                            drawingStroke?.let { stroke ->
                                strokes = strokes + stroke
                                selectedOverlay = OverlaySelection.Stroke(strokes.lastIndex)
                                activeStroke = null
                            }
                            if (tool == OverlayTool.SHAPE) activeShape = null
                            recordBefore(before)
                        }
                    }
                ) {
                    val rect = fittedImageRect(size.width, size.height, preview.width.toFloat(), preview.height.toFloat())
                    val canvas = drawContext.canvas.nativeCanvas
                    val saveCount = canvas.save()
                    canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
                    fun drawStroke(stroke: EditorStroke, highlight: Boolean = false) {
                        if (stroke.points.isEmpty()) return
                        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                            color = if (highlight) 0xCC00E5FF.toInt() else stroke.color
                            style = NativePaint.Style.STROKE
                            strokeWidth = (stroke.width * rect.width).coerceAtLeast(1f) + if (highlight) 8.dp.toPx() else 0f
                            strokeCap = NativePaint.Cap.ROUND; strokeJoin = NativePaint.Join.ROUND
                        }
                        val path = NativePath().apply {
                            moveTo(rect.left + stroke.points.first().x * rect.width, rect.top + stroke.points.first().y * rect.height)
                            stroke.points.drop(1).forEach { lineTo(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                        }
                        if (stroke.points.size == 1) canvas.drawCircle(rect.left + stroke.points[0].x * rect.width, rect.top + stroke.points[0].y * rect.height, paint.strokeWidth / 2f, NativePaint(paint).apply { style = NativePaint.Style.FILL }) else canvas.drawPath(path, paint)
                    }
                    fun drawShape(shape: EditorShape, highlight: Boolean = false) {
                        val sx = rect.left + shape.start.x * rect.width; val sy = rect.top + shape.start.y * rect.height
                        val ex = rect.left + shape.end.x * rect.width; val ey = rect.top + shape.end.y * rect.height
                        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                            color = if (highlight) 0xCC00E5FF.toInt() else shape.color; style = NativePaint.Style.STROKE
                            strokeWidth = (shape.width * rect.width).coerceAtLeast(1f) + if (highlight) 8.dp.toPx() else 0f
                            strokeCap = NativePaint.Cap.ROUND; strokeJoin = NativePaint.Join.ROUND
                        }
                        drawShapeNative(canvas, shape.kind, sx, sy, ex, ey, paint)
                    }
                    effects.forEachIndexed { index, effect -> drawEffectRegion(canvas, rect, effect, selectedOverlay == OverlaySelection.Effect(index), 2.dp.toPx()) }
                    activeRegionEffect?.let { drawEffectRegion(canvas, rect, it, true, 2.dp.toPx()) }
                    strokes.forEachIndexed { index, stroke ->
                        if (selectedOverlay == OverlaySelection.Stroke(index)) drawStroke(stroke, true)
                        drawStroke(stroke)
                    }
                    shapes.forEachIndexed { index, shape ->
                        if (selectedOverlay == OverlaySelection.Shape(index)) drawShape(shape, true)
                        drawShape(shape)
                    }
                    activeStroke?.let { drawStroke(it) }; activeShape?.let { drawShape(it) }
                    texts.forEachIndexed { index, overlay ->
                        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                            color = overlay.color; textSize = overlay.size * rect.width
                            typeface = PhotoEditor.fontTypeface(overlay.font); setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
                        }
                        val x = rect.left + overlay.x * rect.width; val centerY = rect.top + overlay.y * rect.height
                        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f; val textWidth = paint.measureText(overlay.text)
                        if (selectedOverlay == OverlaySelection.Text(index)) {
                            val padding = 6.dp.toPx()
                            val bounds = Rect(x - textWidth / 2f - padding, baseline + paint.ascent() - padding, x + textWidth / 2f + padding, baseline + paint.descent() + padding)
                            val border = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); style = NativePaint.Style.STROKE; strokeWidth = 2.dp.toPx() }
                            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, border)
                        }
                        canvas.drawText(overlay.text, x - textWidth / 2f, baseline, paint)
                    }
                    stickers.forEachIndexed { index, sticker -> drawSticker(canvas, rect, sticker, selectedOverlay == OverlaySelection.Sticker(index)) }
                    activeCropRect?.let { crop ->
                        val bounds = crop.normalized()
                        val left = rect.left + bounds.left * rect.width; val top = rect.top + bounds.top * rect.height
                        val right = rect.left + bounds.right * rect.width; val bottom = rect.top + bounds.bottom * rect.height
                        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE; style = NativePaint.Style.STROKE; strokeWidth = 2.dp.toPx()
                            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()), 0f)
                        }
                        canvas.drawRect(left, top, right, bottom, paint)
                    }
                    canvas.restoreToCount(saveCount)
                }
            }
            when (tool) {
                OverlayTool.DRAW -> Text(stringResource(R.string.s_draw_on_the_photo_with_your_finger_lift_to_finish_a_stroke), style = MaterialTheme.typography.bodySmall)
                OverlayTool.ERASE -> Text(stringResource(R.string.s_swipe_over_drawing_strokes_to_erase_them), style = MaterialTheme.typography.bodySmall)
                OverlayTool.BLUR -> Text(stringResource(R.string.s_drag_a_region_to_blur_it_select_and_move_the_region_later), style = MaterialTheme.typography.bodySmall)
                OverlayTool.PIXELATE -> Text(stringResource(R.string.s_drag_a_region_to_pixelate_it_select_and_move_the_region_later), style = MaterialTheme.typography.bodySmall)
                OverlayTool.PLACE_STICKER -> Text("Tap the photo to place $pendingSticker.", style = MaterialTheme.typography.bodySmall)
                OverlayTool.SHAPE -> Text("Drag on the photo to place a ${shapeKind.name.lowercase()}.", style = MaterialTheme.typography.bodySmall)
                OverlayTool.FREE_CROP -> Text(stringResource(R.string.s_drag_to_select_a_freeform_crop_the_crop_is_applied_on_release), style = MaterialTheme.typography.bodySmall)
                OverlayTool.PLACE_TEXT -> Text("Tap the photo to place “$pendingText”.", style = MaterialTheme.typography.bodySmall)
                OverlayTool.SELECT -> Text(stringResource(R.string.s_tap_an_overlay_to_select_it_or_drag_it_to_move), style = MaterialTheme.typography.bodySmall)
                OverlayTool.NONE -> Unit
            }

            Text(stringResource(R.string.s_drawing_text), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = tool == OverlayTool.DRAW, onClick = { tool = if (tool == OverlayTool.DRAW) OverlayTool.NONE else OverlayTool.DRAW }, label = { Text(stringResource(R.string.s_draw)) })
                FilterChip(selected = tool == OverlayTool.ERASE, onClick = { tool = if (tool == OverlayTool.ERASE) OverlayTool.NONE else OverlayTool.ERASE }, label = { Text(stringResource(R.string.s_eraser)) })
                FilterChip(selected = tool == OverlayTool.SELECT, onClick = { tool = if (tool == OverlayTool.SELECT) OverlayTool.NONE else OverlayTool.SELECT }, label = { Text(stringResource(R.string.s_select_edit)) })
                FilterChip(selected = tool == OverlayTool.BLUR, onClick = { tool = if (tool == OverlayTool.BLUR) OverlayTool.NONE else OverlayTool.BLUR }, label = { Text(stringResource(R.string.s_blur_region)) })
                FilterChip(selected = tool == OverlayTool.PIXELATE, onClick = { tool = if (tool == OverlayTool.PIXELATE) OverlayTool.NONE else OverlayTool.PIXELATE }, label = { Text(stringResource(R.string.s_pixelate_region)) })
                OutlinedButton(onClick = { textEntry = ""; textDialog = true }) { Text(stringResource(R.string.s_add_text)) }
                OutlinedButton(enabled = undoHistory.isNotEmpty(), onClick = { undo() }) { Text(stringResource(R.string.s_undo)) }
                OutlinedButton(enabled = redoHistory.isNotEmpty(), onClick = { redo() }) { Text(stringResource(R.string.s_redo)) }
                OutlinedButton(enabled = selectedOverlay != null, onClick = {
                    val before = snapshot()
                    when (val selection = selectedOverlay) {
                        is OverlaySelection.Stroke -> strokes = strokes.filterIndexed { index, _ -> index != selection.index }
                        is OverlaySelection.Shape -> shapes = shapes.filterIndexed { index, _ -> index != selection.index }
                        is OverlaySelection.Text -> texts = texts.filterIndexed { index, _ -> index != selection.index }
                        is OverlaySelection.Sticker -> stickers = stickers.filterIndexed { index, _ -> index != selection.index }
                        is OverlaySelection.Effect -> effects = effects.filterIndexed { index, _ -> index != selection.index }
                        null -> Unit
                    }
                    selectedOverlay = null; recordBefore(before)
                }) { Text(stringResource(R.string.s_delete_selected)) }
                OutlinedButton(enabled = hasOverlays, onClick = {
                    val before = snapshot()
                    strokes = emptyList(); shapes = emptyList(); texts = emptyList(); stickers = emptyList(); effects = emptyList(); activeStroke = null; activeShape = null; activeRegionEffect = null; selectedOverlay = null
                    recordBefore(before)
                }) { Text(stringResource(R.string.s_clear_all)) }
            }
            Text(stringResource(R.string.s_built_in_stickers), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                editorStickers.forEach { glyph ->
                    OutlinedButton(onClick = { pendingSticker = glyph; tool = OverlayTool.PLACE_STICKER }) { Text(glyph, style = MaterialTheme.typography.titleLarge) }
                }
            }
            Text(stringResource(R.string.s_text_font), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorFont.entries.forEach { font ->
                    FilterChip(selected = selectedFont == font, onClick = {
                        val before = snapshot(); selectedFont = font
                        (selectedOverlay as? OverlaySelection.Text)?.let { sel -> texts = texts.mapIndexed { i, value -> if (i == sel.index) value.copy(font = font) else value } }
                        recordBefore(before)
                    }, label = { Text(font.label, fontFamily = when (font) { EditorFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif; EditorFont.MONOSPACE -> androidx.compose.ui.text.font.FontFamily.Monospace; EditorFont.DISPLAY -> androidx.compose.ui.text.font.FontFamily.Cursive; else -> androidx.compose.ui.text.font.FontFamily.SansSerif }) })
                }
            }
            Text(stringResource(R.string.s_shapes), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorShapeKind.entries.forEach { kind ->
                    FilterChip(selected = tool == OverlayTool.SHAPE && shapeKind == kind, onClick = { shapeKind = kind; tool = OverlayTool.SHAPE }, label = { Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                editorColors.forEach { color ->
                    FilterChip(selected = selectedColor == color.value, onClick = {
                        val before = snapshot(); selectedColor = color.value
                        when (val selection = selectedOverlay) {
                            is OverlaySelection.Stroke -> strokes = strokes.mapIndexed { index, stroke -> if (index == selection.index) stroke.copy(color = color.value) else stroke }
                            is OverlaySelection.Shape -> shapes = shapes.mapIndexed { index, shape -> if (index == selection.index) shape.copy(color = color.value) else shape }
                            is OverlaySelection.Text -> texts = texts.mapIndexed { index, text -> if (index == selection.index) text.copy(color = color.value) else text }
                            is OverlaySelection.Sticker -> stickers = stickers.mapIndexed { index, sticker -> if (index == selection.index) sticker.copy(color = color.value) else sticker }
                            is OverlaySelection.Effect, null -> Unit
                        }
                        recordBefore(before)
                    }, label = { Text(color.label) })
                }
            }
            val selectedStroke = selectedOverlay is OverlaySelection.Stroke
            val selectedShape = selectedOverlay is OverlaySelection.Shape
            val selectedText = selectedOverlay is OverlaySelection.Text
            val selectedSticker = selectedOverlay is OverlaySelection.Sticker
            Text(if (selectedStroke || selectedShape) "Selected overlay width: ${brushSize.toInt()} dp" else "Brush / shape width: ${brushSize.toInt()} dp")
            Slider(value = brushSize, onValueChange = { value ->
                beginSliderEdit(); brushSize = value
                when (val selection = selectedOverlay) {
                    is OverlaySelection.Stroke -> strokes = strokes.mapIndexed { index, stroke -> if (index == selection.index) stroke.copy(width = value * densityScale / displayImageWidthPx) else stroke }
                    is OverlaySelection.Shape -> shapes = shapes.mapIndexed { index, shape -> if (index == selection.index) shape.copy(width = value * densityScale / displayImageWidthPx) else shape }
                    else -> Unit
                }
            }, onValueChangeFinished = { finishSliderEdit() }, valueRange = 2f..18f)
            val maxTextSize = if (selectedSticker) 0.20f else 0.12f
            Text(if (selectedText || selectedSticker) "Selected text / sticker size" else "New text size")
            Slider(value = textSize.coerceIn(0.04f, maxTextSize), onValueChange = { value ->
                beginSliderEdit(); textSize = value
                (selectedOverlay as? OverlaySelection.Text)?.let { selection -> texts = texts.mapIndexed { index, text -> if (index == selection.index) text.copy(size = value.coerceAtMost(0.12f)) else text } }
                (selectedOverlay as? OverlaySelection.Sticker)?.let { selection -> stickers = stickers.mapIndexed { index, sticker -> if (index == selection.index) sticker.copy(size = value) else sticker } }
            }, onValueChangeFinished = { finishSliderEdit() }, valueRange = 0.04f..maxTextSize)
            (selectedOverlay as? OverlaySelection.Effect)?.let { selection ->
                effects.getOrNull(selection.index)?.let { region ->
                    val normalized = region.normalized()
                    val currentMajor = maxOf(normalized.end.x - normalized.start.x, normalized.end.y - normalized.start.y).coerceIn(0.03f, 1f)
                    Text(stringResource(R.string.s_selected_effect_region_size))
                    Slider(value = currentMajor, onValueChange = { targetMajor ->
                        beginSliderEdit()
                        val current = effects.getOrNull(selection.index)?.normalized()
                        if (current != null) {
                            val centerX = (current.start.x + current.end.x) / 2f
                            val centerY = (current.start.y + current.end.y) / 2f
                            val oldW = (current.end.x - current.start.x).coerceAtLeast(0.001f)
                            val oldH = (current.end.y - current.start.y).coerceAtLeast(0.001f)
                            val aspect = oldW / oldH
                            var newW = if (aspect >= 1f) targetMajor else targetMajor * aspect
                            var newH = if (aspect >= 1f) targetMajor / aspect else targetMajor
                            val fit = minOf(1f, (2f * minOf(centerX, 1f - centerX) / newW).coerceAtLeast(0f), (2f * minOf(centerY, 1f - centerY) / newH).coerceAtLeast(0f))
                            newW *= fit; newH *= fit
                            effects = effects.mapIndexed { index, item -> if (index == selection.index) item.copy(start = EditorPoint(centerX - newW / 2f, centerY - newH / 2f), end = EditorPoint(centerX + newW / 2f, centerY + newH / 2f)) else item }
                        }
                    }, onValueChangeFinished = { finishSliderEdit() }, valueRange = 0.03f..1f)
                }
            }

            Text(stringResource(R.string.s_filters), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditFilter.entries.forEach { f -> FilterChip(selected = params.filter == f, onClick = { val before = snapshot(); params = params.copy(filter = f); recordBefore(before) }, label = { Text(f.label) }) }
            }
            Text(stringResource(R.string.s_crop), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CropRatio.entries.forEach { c ->
                    FilterChip(selected = params.crop == c && params.freeCrop == null, onClick = {
                        val before = snapshot(); params = params.copy(crop = c, freeCrop = null); recordBefore(before)
                    }, label = { Text(c.label) })
                }
                FilterChip(selected = tool == OverlayTool.FREE_CROP || params.freeCrop != null, onClick = {
                    val before = snapshot()
                    params = params.copy(crop = CropRatio.ORIGINAL, freeCrop = null)
                    activeCropRect = null
                    tool = if (tool == OverlayTool.FREE_CROP) OverlayTool.NONE else OverlayTool.FREE_CROP
                    recordBefore(before)
                }, label = { Text(stringResource(R.string.s_free_crop)) })
            }
            Text("Brightness: ${params.brightness.toInt()}")
            Slider(value = params.brightness, onValueChange = { value -> beginSliderEdit(); params = params.copy(brightness = value) }, onValueChangeFinished = { finishSliderEdit() }, valueRange = -100f..100f)
            Text("Contrast: ${"%.2f".format(params.contrast)}")
            Slider(value = params.contrast, onValueChange = { value -> beginSliderEdit(); params = params.copy(contrast = value) }, onValueChangeFinished = { finishSliderEdit() }, valueRange = 0.5f..1.5f)
            Text("Saturation: ${"%.2f".format(params.saturation)}")
            Slider(value = params.saturation, onValueChange = { value -> beginSliderEdit(); params = params.copy(saturation = value) }, onValueChangeFinished = { finishSliderEdit() }, valueRange = 0f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { val before = snapshot(); params = params.copy(rotation = (params.rotation + 90f) % 360f); recordBefore(before) }) { Text(stringResource(R.string.s_rotate_90)) }
                OutlinedButton(onClick = { val before = snapshot(); params = params.copy(flipH = !params.flipH); recordBefore(before) }) { Text(stringResource(R.string.s_flip)) }
                OutlinedButton(onClick = { val before = snapshot(); params = params.copy(flipV = !params.flipV); recordBefore(before) }) { Text(stringResource(R.string.s_flip_2)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = hasEdits, onClick = {
                    val before = snapshot(); params = EditParams(); strokes = emptyList(); shapes = emptyList(); texts = emptyList(); stickers = emptyList(); effects = emptyList()
                    activeStroke = null; activeShape = null; activeCropRect = null; activeRegionEffect = null; selectedOverlay = null; tool = OverlayTool.NONE; pendingText = ""; pendingSticker = ""
                    recordBefore(before)
                }) { Text(stringResource(R.string.s_reset)) }
                Button(enabled = !saving && hasEdits, onClick = {
                    val snapshotParams = params
                    val strokeSnapshot = strokes.toList(); val shapeSnapshot = shapes.toList(); val textSnapshot = texts.toList()
                    val stickerSnapshot = stickers.toList(); val effectSnapshot = effects.toList()
                    scope.launch {
                        saving = true
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val edited = PhotoEditor.edit(bitmap, snapshotParams)
                                try {
                                    val composed = PhotoEditor.renderOverlays(edited, strokeSnapshot, textSnapshot, shapeSnapshot, stickerSnapshot, effectSnapshot)
                                    try { PhotoEditor.save(context, composed) } finally { composed.recycle() }
                                } finally { edited.recycle() }
                            } catch (_: Throwable) { null }
                        }
                        saving = false
                        Toast.makeText(context, if (result != null) "Saved copy to Pictures/MyGallery" else "Save failed", Toast.LENGTH_LONG).show()
                        if (result != null) onSaved()
                    }
                }) { Text(if (saving) "Saving…" else "Save edited copy") }
            }
        } else Text(if (loadFailed) "Could not open this image." else "Loading image…")
    }

    if (textDialog) {
        AlertDialog(
            onDismissRequest = { textDialog = false }, title = { Text(stringResource(R.string.s_add_text)) },
            text = { OutlinedTextField(value = textEntry, onValueChange = { textEntry = it }, label = { Text(stringResource(R.string.s_text)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val entered = textEntry.trim()
                if (entered.isNotEmpty()) { pendingText = entered; tool = OverlayTool.PLACE_TEXT }
                textDialog = false
            }) { Text(stringResource(R.string.s_continue)) } },
            dismissButton = { TextButton(onClick = { textDialog = false }) { Text(stringResource(R.string.s_cancel)) } }
        )
    }
}

private fun drawShapeNative(canvas: android.graphics.Canvas, kind: EditorShapeKind, sx: Float, sy: Float, ex: Float, ey: Float, paint: NativePaint) {
    val left = minOf(sx, ex); val right = maxOf(sx, ex); val top = minOf(sy, ey); val bottom = maxOf(sy, ey)
    when (kind) {
        EditorShapeKind.LINE -> canvas.drawLine(sx, sy, ex, ey, paint)
        EditorShapeKind.ARROW -> {
            canvas.drawLine(sx, sy, ex, ey, paint)
            val angle = kotlin.math.atan2((ey - sy).toDouble(), (ex - sx).toDouble()).toFloat()
            val head = (paint.strokeWidth * 4f).coerceAtLeast(12f)
            val path = NativePath().apply {
                moveTo(ex, ey)
                lineTo(ex - head * kotlin.math.cos(angle - 0.55f), ey - head * kotlin.math.sin(angle - 0.55f))
                moveTo(ex, ey)
                lineTo(ex - head * kotlin.math.cos(angle + 0.55f), ey - head * kotlin.math.sin(angle + 0.55f))
            }
            canvas.drawPath(path, paint)
        }
        EditorShapeKind.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
        EditorShapeKind.ELLIPSE -> canvas.drawOval(left, top, right, bottom, paint)
        EditorShapeKind.ROUNDED_RECTANGLE -> canvas.drawRoundRect(left, top, right, bottom, minOf(right - left, bottom - top) * .15f, minOf(right - left, bottom - top) * .15f, paint)
        else -> {
            val count = when (kind) { EditorShapeKind.TRIANGLE -> 3; EditorShapeKind.DIAMOND -> 4; EditorShapeKind.PENTAGON -> 5; EditorShapeKind.HEXAGON -> 6; EditorShapeKind.STAR -> 10; else -> 0 }
            if (count > 0) {
                val cx = (left + right) / 2f; val cy = (top + bottom) / 2f; val rx = (right - left) / 2f; val ry = (bottom - top) / 2f
                val path = NativePath()
                for (i in 0 until count) {
                    val angle = -Math.PI / 2 + i * 2 * Math.PI / count; val factor = if (kind == EditorShapeKind.STAR && i % 2 == 1) .42f else 1f
                    val x = cx + (rx * factor * kotlin.math.cos(angle)).toFloat(); val y = cy + (ry * factor * kotlin.math.sin(angle)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); canvas.drawPath(path, paint)
            }
        }
    }
}

private fun drawEffectRegion(canvas: android.graphics.Canvas, rect: Rect, effect: EditorRegionEffect, selected: Boolean, strokeWidth: Float) {
    val e = effect.normalized(); val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        color = if (selected) 0xFFFFC107.toInt() else 0xCCFFFFFF.toInt(); style = NativePaint.Style.STROKE; this.strokeWidth = strokeWidth
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
    }
    canvas.drawRect(rect.left + e.start.x * rect.width, rect.top + e.start.y * rect.height, rect.left + e.end.x * rect.width, rect.top + e.end.y * rect.height, paint)
}

private fun drawSticker(canvas: android.graphics.Canvas, rect: Rect, sticker: EditorSticker, selected: Boolean) {
    val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply { color = sticker.color; textSize = sticker.size * rect.width; typeface = Typeface.DEFAULT }
    val x = rect.left + sticker.x * rect.width; val centerY = rect.top + sticker.y * rect.height
    val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
    if (selected) {
        val pad = 5f; val w = paint.measureText(sticker.glyph)
        val border = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); style = NativePaint.Style.STROKE; strokeWidth = 2f }
        canvas.drawRect(x - w / 2f - pad, baseline + paint.ascent() - pad, x + w / 2f + pad, baseline + paint.descent() + pad, border)
    }
    canvas.drawText(sticker.glyph, x - paint.measureText(sticker.glyph) / 2f, baseline, paint)
}

private fun findOverlayAt(point: EditorPoint, rect: Rect, strokes: List<EditorStroke>, shapes: List<EditorShape>, texts: List<EditorText>, stickers: List<EditorSticker>, effects: List<EditorRegionEffect>, touchSlopPx: Float): OverlaySelection? {
    val px = point.x * rect.width; val py = point.y * rect.height
    for (index in stickers.indices.reversed()) {
        val sticker = stickers[index]; val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply { textSize = sticker.size * rect.width; typeface = Typeface.DEFAULT }
        val cx = sticker.x * rect.width; val cy = sticker.y * rect.height; val halfW = paint.measureText(sticker.glyph) / 2f + touchSlopPx
        val halfH = (paint.descent() - paint.ascent()) / 2f + touchSlopPx
        if (px in (cx - halfW)..(cx + halfW) && py in (cy - halfH)..(cy + halfH)) return OverlaySelection.Sticker(index)
    }
    for (index in effects.indices.reversed()) {
        val effect = effects[index].normalized()
        val l = effect.start.x * rect.width - touchSlopPx; val r = effect.end.x * rect.width + touchSlopPx
        val t = effect.start.y * rect.height - touchSlopPx; val b = effect.end.y * rect.height + touchSlopPx
        if (px in l..r && py in t..b) return OverlaySelection.Effect(index)
    }
    for (index in texts.indices.reversed()) {
        val text = texts[index]
        val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply { textSize = text.size * rect.width; typeface = PhotoEditor.fontTypeface(text.font) }
        val halfWidth = paint.measureText(text.text) / 2f + touchSlopPx
        val baseline = pyForText(text, rect); val top = baseline + paint.ascent() - touchSlopPx; val bottom = baseline + paint.descent() + touchSlopPx
        val centerX = text.x * rect.width
        if (px in (centerX - halfWidth)..(centerX + halfWidth) && py in top..bottom) return OverlaySelection.Text(index)
    }
    for (index in shapes.indices.reversed()) if (shapeHit(point, shapes[index], rect, touchSlopPx)) return OverlaySelection.Shape(index)
    for (index in strokes.indices.reversed()) {
        val stroke = strokes[index]
        if (strokeHit(point, stroke, rect, touchSlopPx)) return OverlaySelection.Stroke(index)
    }
    return null
}

private fun findStrokeAt(point: EditorPoint, rect: Rect, strokes: List<EditorStroke>, touchSlopPx: Float): Int? {
    for (index in strokes.indices.reversed()) if (strokeHit(point, strokes[index], rect, touchSlopPx)) return index
    return null
}

private fun strokeHit(point: EditorPoint, stroke: EditorStroke, rect: Rect, touchSlopPx: Float): Boolean {
    if (stroke.points.isEmpty()) return false
    val px = point.x * rect.width; val py = point.y * rect.height
    val threshold = maxOf(stroke.width * rect.width / 2f + touchSlopPx, touchSlopPx)
    val pts = stroke.points.map { Offset(it.x * rect.width, it.y * rect.height) }
    return if (pts.size == 1) (Offset(px, py) - pts[0]).getDistance() <= threshold
    else pts.zipWithNext().any { (a, b) -> distanceToSegment(px, py, a.x, a.y, b.x, b.y) <= threshold }
}

private fun shapeHit(point: EditorPoint, shape: EditorShape, rect: Rect, touchSlopPx: Float): Boolean {
    val sx = shape.start.x * rect.width; val sy = shape.start.y * rect.height
    val ex = shape.end.x * rect.width; val ey = shape.end.y * rect.height
    val px = point.x * rect.width; val py = point.y * rect.height
    val threshold = maxOf(shape.width * rect.width / 2f + touchSlopPx, touchSlopPx)
    if (shape.kind == EditorShapeKind.LINE || shape.kind == EditorShapeKind.ARROW)
        return distanceToSegment(px, py, sx, sy, ex, ey) <= threshold || (shape.kind == EditorShapeKind.ARROW && distanceToSegment(px, py, ex, ey, sx, sy) <= threshold * 2)
    val l = minOf(sx, ex); val r = maxOf(sx, ex); val t = minOf(sy, ey); val b = maxOf(sy, ey)
    if (px < l - threshold || px > r + threshold || py < t - threshold || py > b + threshold) return false
    return if (shape.kind == EditorShapeKind.RECTANGLE) {
        minOf(kotlin.math.abs(px - l), kotlin.math.abs(px - r), kotlin.math.abs(py - t), kotlin.math.abs(py - b)) <= threshold
    } else {
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        if (rx <= 0f || ry <= 0f) false else kotlin.math.abs(((px - (l + r) / 2f) / rx).let { it * it } + ((py - (t + b) / 2f) / ry).let { it * it } - 1f) * minOf(rx, ry) <= threshold
    }
}

private fun pyForText(text: EditorText, rect: Rect): Float {
    val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply { textSize = text.size * rect.width; typeface = PhotoEditor.fontTypeface(text.font) }
    val centerY = text.y * rect.height
    return centerY - (paint.ascent() + paint.descent()) / 2f
}

private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax; val dy = by - ay
    if (dx == 0f && dy == 0f) return (Offset(px, py) - Offset(ax, ay)).getDistance()
    val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    return (Offset(px, py) - Offset(ax + t * dx, ay + t * dy)).getDistance()
}

private fun fittedImageRect(canvasWidth: Float, canvasHeight: Float, imageWidth: Float, imageHeight: Float): Rect {
    val scale = minOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
    val width = imageWidth * scale; val height = imageHeight * scale
    val left = (canvasWidth - width) / 2f; val top = (canvasHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}
