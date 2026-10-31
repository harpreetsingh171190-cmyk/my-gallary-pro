package com.mygallery.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


enum class EditFilter(val label: String) { NONE("Original"), MONO("B&W"), SEPIA("Sepia"), WARM("Warm"), COOL("Cool"), VIVID("Vivid"), FADE("Fade") }

enum class CropRatio(val label: String, val w: Int, val h: Int) {
    ORIGINAL("Original", 0, 0), SQUARE("1:1", 1, 1), R4_3("4:3", 4, 3), R3_4("3:4", 3, 4), R16_9("16:9", 16, 9), R9_16("9:16", 9, 16)
}

/** Overlay coordinates are normalized to the final, post-crop image bounds. */
data class EditorPoint(val x: Float, val y: Float)
data class EditorStroke(val points: List<EditorPoint>, val color: Int, val width: Float)
enum class EditorFont(val label: String, val family: String) {
    SANS("Sans", "sans-serif"), SERIF("Serif", "serif"), MONOSPACE("Mono", "monospace"), DISPLAY("Display", "cursive")
}
data class EditorText(val text: String, val x: Float, val y: Float, val color: Int, val size: Float, val font: EditorFont = EditorFont.SANS)
data class EditorSticker(val glyph: String, val x: Float, val y: Float, val color: Int, val size: Float = 0.12f)
enum class EditorShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE, ROUNDED_RECTANGLE, TRIANGLE, DIAMOND, PENTAGON, STAR, HEXAGON }
data class EditorShape(val kind: EditorShapeKind, val start: EditorPoint, val end: EditorPoint, val color: Int, val width: Float)
enum class EditorEffectKind { BLUR, PIXELATE }
data class EditorRegionEffect(val kind: EditorEffectKind, val start: EditorPoint, val end: EditorPoint) {
    fun normalized() = EditorRegionEffect(kind,
        EditorPoint(minOf(start.x, end.x).coerceIn(0f, 1f), minOf(start.y, end.y).coerceIn(0f, 1f)),
        EditorPoint(maxOf(start.x, end.x).coerceIn(0f, 1f), maxOf(start.y, end.y).coerceIn(0f, 1f)))
}
/** Normalized crop bounds in the rotated/flipped image, before any crop is applied. */
data class EditorCropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun normalized(): EditorCropRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        return EditorCropRect(minOf(l, r), minOf(t, b), maxOf(l, r), maxOf(t, b))
    }
}

/** All non-destructive edit settings; the defaults mean "no change". */
data class EditParams(
    val rotation: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val filter: EditFilter = EditFilter.NONE,
    val crop: CropRatio = CropRatio.ORIGINAL,
    val freeCrop: EditorCropRect? = null
) { val isDefault: Boolean get() = this == EditParams() }

object PhotoEditor {
    /** Decodes a down-sampled bitmap (avoids OutOfMemory on large photos) and applies EXIF orientation. */
    fun load(context: Context, uri: Uri, maxDimension: Int = 4096): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = resolver.openInputStream(uri)?.use { stream ->
            runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        return applyOrientation(decoded, orientation)
    }

    internal fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    /** Never recycles or modifies [original]. Order: rotate -> flip -> crop -> colour. */
    fun edit(original: Bitmap, p: EditParams): Bitmap {
        val geo = Matrix().apply {
            if (p.rotation % 360f != 0f) postRotate(p.rotation)
            if (p.flipH) postScale(-1f, 1f)
            if (p.flipV) postScale(1f, -1f)
        }
        var current = if (geo.isIdentity) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height, geo, true)
        if (p.freeCrop != null) {
            val crop = p.freeCrop.normalized()
            val left = (crop.left * current.width).toInt().coerceIn(0, current.width - 1)
            val top = (crop.top * current.height).toInt().coerceIn(0, current.height - 1)
            val right = (crop.right * current.width).toInt().coerceIn(left + 1, current.width)
            val bottom = (crop.bottom * current.height).toInt().coerceIn(top + 1, current.height)
            val cropped = Bitmap.createBitmap(current, left, top, right - left, bottom - top)
            if (cropped !== current && current !== original) current.recycle()
            current = cropped
        } else if (p.crop != CropRatio.ORIGINAL) {
            val target = p.crop.w.toFloat() / p.crop.h.toFloat()
            val w = current.width; val h = current.height
            val cw: Int; val ch: Int
            if (w.toFloat() / h > target) { ch = h; cw = (h * target).toInt().coerceIn(1, w) }
            else { cw = w; ch = (w / target).toInt().coerceIn(1, h) }
            val cropped = Bitmap.createBitmap(current, (w - cw) / 2, (h - ch) / 2, cw, ch)
            if (cropped !== current && current !== original) current.recycle()
            current = cropped
        }
        val colour = ColorMatrix().apply {
            setSaturation(p.saturation)
            postConcat(contrastBrightness(p.contrast, p.brightness))
            filterMatrix(p.filter)?.let { postConcat(it) }
        }
        val out = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(current, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(colour) })
        if (current !== original) current.recycle()
        return out
    }

    /** Applies normalized rectangular effects to a copy; pixelation/blur are previewed and saved identically. */
    fun renderEffects(base: Bitmap, effects: List<EditorRegionEffect>): Bitmap {
        if (effects.isEmpty()) return base
        val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
        val canvas = Canvas(out)
        effects.forEach { raw ->
            val effect = raw.normalized()
            val left = (effect.start.x * base.width).toInt().coerceIn(0, base.width - 1)
            val top = (effect.start.y * base.height).toInt().coerceIn(0, base.height - 1)
            val right = (effect.end.x * base.width).toInt().coerceIn(left + 1, base.width)
            val bottom = (effect.end.y * base.height).toInt().coerceIn(top + 1, base.height)
            val width = right - left; val height = bottom - top
            if (width < 2 || height < 2) return@forEach
            val sampleFactor = if (effect.kind == EditorEffectKind.PIXELATE) 14 else 7
            val smallW = (width / sampleFactor).coerceAtLeast(1)
            val smallH = (height / sampleFactor).coerceAtLeast(1)
            val sample = Bitmap.createBitmap(out, left, top, width, height)
            val small = Bitmap.createScaledBitmap(sample, smallW, smallH, effect.kind != EditorEffectKind.PIXELATE)
            if (sample !== out) sample.recycle()
            val paint = Paint(if (effect.kind == EditorEffectKind.PIXELATE) 0 else Paint.FILTER_BITMAP_FLAG)
            val save = canvas.save()
            canvas.clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            canvas.drawBitmap(small, null, RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), paint)
            canvas.restoreToCount(save)
            small.recycle()
        }
        return out
    }

    private fun contrastBrightness(contrast: Float, brightness: Float): ColorMatrix {
        val t = 128f * (1f - contrast) + brightness
        return ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    private fun filterMatrix(filter: EditFilter): ColorMatrix? = when (filter) {
        EditFilter.NONE -> null
        EditFilter.MONO -> ColorMatrix().apply { setSaturation(0f) }
        EditFilter.SEPIA -> ColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        EditFilter.WARM -> ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 8f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.9f, 0f, -8f, 0f, 0f, 0f, 1f, 0f))
        EditFilter.COOL -> ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, -6f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f))
        EditFilter.VIVID -> ColorMatrix().apply { setSaturation(1.35f); postConcat(contrastBrightness(1.1f, 0f)) }
        EditFilter.FADE -> contrastBrightness(0.85f, 12f)
    }

    fun fontTypeface(font: EditorFont): android.graphics.Typeface = android.graphics.Typeface.create(font.family, android.graphics.Typeface.BOLD)

    /** Returns a new bitmap with normalized effects, drawing, shapes, stickers, and text rendered on top. */
    fun renderOverlays(
        base: Bitmap,
        strokes: List<EditorStroke>,
        texts: List<EditorText>,
        shapes: List<EditorShape> = emptyList(),
        stickers: List<EditorSticker> = emptyList(),
        effects: List<EditorRegionEffect> = emptyList()
    ): Bitmap {
        val effected = renderEffects(base, effects)
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(effected, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (effected !== base) effected.recycle()
        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color; style = Paint.Style.STROKE
                strokeWidth = (stroke.width * base.width).coerceAtLeast(1f)
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            val path = Path().apply {
                moveTo(stroke.points.first().x * base.width, stroke.points.first().y * base.height)
                stroke.points.drop(1).forEach { lineTo(it.x * base.width, it.y * base.height) }
            }
            if (stroke.points.size == 1) canvas.drawCircle(stroke.points[0].x * base.width, stroke.points[0].y * base.height, paint.strokeWidth / 2f, Paint(paint).apply { style = Paint.Style.FILL }) else canvas.drawPath(path, paint)
        }
        shapes.forEach { shape ->
            val sx = shape.start.x * base.width; val sy = shape.start.y * base.height
            val ex = shape.end.x * base.width; val ey = shape.end.y * base.height
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shape.color; style = Paint.Style.STROKE
                strokeWidth = (shape.width * base.width).coerceAtLeast(1f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            drawEditorShape(canvas, shape.kind, sx, sy, ex, ey, paint)
        }
        stickers.forEach { sticker ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sticker.color; textSize = sticker.size * base.width; typeface = android.graphics.Typeface.DEFAULT }
            val x = sticker.x * base.width; val centerY = sticker.y * base.height
            val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(sticker.glyph, x - paint.measureText(sticker.glyph) / 2f, baseline, paint)
        }
        texts.forEach { overlay ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = overlay.color; textSize = overlay.size * base.width; typeface = fontTypeface(overlay.font)
                setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
            }
            val x = overlay.x * base.width; val centerY = overlay.y * base.height
            val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(overlay.text, x - paint.measureText(overlay.text) / 2f, baseline, paint)
        }
        return out
    }

    private fun drawEditorShape(canvas: Canvas, kind: EditorShapeKind, sx: Float, sy: Float, ex: Float, ey: Float, paint: Paint) {
        val left = minOf(sx, ex); val right = maxOf(sx, ex); val top = minOf(sy, ey); val bottom = maxOf(sy, ey)
        when (kind) {
            EditorShapeKind.LINE -> canvas.drawLine(sx, sy, ex, ey, paint)
            EditorShapeKind.ARROW -> {
                canvas.drawLine(sx, sy, ex, ey, paint)
                val angle = kotlin.math.atan2((ey - sy).toDouble(), (ex - sx).toDouble()).toFloat()
                val head = (paint.strokeWidth * 4f).coerceAtLeast(12f)
                val path = Path().apply {
                    moveTo(ex, ey); lineTo(ex - head * cos(angle - 0.55f), ey - head * sin(angle - 0.55f))
                    moveTo(ex, ey); lineTo(ex - head * cos(angle + 0.55f), ey - head * sin(angle + 0.55f))
                }
                canvas.drawPath(path, paint)
            }
            EditorShapeKind.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
            EditorShapeKind.ELLIPSE -> canvas.drawOval(left, top, right, bottom, paint)
            EditorShapeKind.ROUNDED_RECTANGLE -> canvas.drawRoundRect(left, top, right, bottom, minOf(right - left, bottom - top) * 0.15f, minOf(right - left, bottom - top) * 0.15f, paint)
            else -> {
                val vertices = when (kind) {
                    EditorShapeKind.TRIANGLE -> 3
                    EditorShapeKind.DIAMOND -> 4
                    EditorShapeKind.PENTAGON -> 5
                    EditorShapeKind.HEXAGON -> 6
                    EditorShapeKind.STAR -> 10
                    else -> 0
                }
                if (vertices > 0) {
                    val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
                    val rx = (right - left) / 2f; val ry = (bottom - top) / 2f
                    val path = Path()
                    for (i in 0 until vertices) {
                        val angle = -Math.PI / 2 + i * 2 * Math.PI / vertices
                        val radius = if (kind == EditorShapeKind.STAR && i % 2 == 1) 0.42f else 1f
                        val x = cx + (rx * radius * cos(angle)).toFloat(); val y = cy + (ry * radius * sin(angle)).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); canvas.drawPath(path, paint)
                }
            }
        }
    }

    fun save(context: Context, bitmap: Bitmap): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MyGallery_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyGallery")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: false
            if (!ok) throw IOException("JPEG compression failed")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null); null
        }
    }
}
