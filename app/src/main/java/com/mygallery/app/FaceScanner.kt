package com.mygallery.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds faces with ML Kit (on device) and turns each face into an embedding with a TensorFlow Lite
 * model. Scanning is blocked until the model file exists in assets (see the README).
 */
object FaceScanner {
    private const val MODEL = "face_embedder.tflite"
    private const val INPUT = 112
    private const val MAX_PER_RUN = 300
    private const val CROP_MARGIN = 0.25f

    fun modelAvailable(context: Context): Boolean =
        try {
            context.assets.open(MODEL).use { true }
        } catch (_: Exception) {
            false
        }

    /** Scans up to [MAX_PER_RUN] photos not scanned before. Each photo is scanned once. */
    suspend fun scan(context: Context, media: List<GalleryMedia>, onProgress: (Int, Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val index = FaceStore.load(context)
            val todo = media.filter { !it.isVideo && it.uri.toString() !in index.scanned }.take(MAX_PER_RUN)
            val interpreter = Interpreter(loadModel(context))
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.08f)
                    .build(),
            )
            try {
                todo.forEachIndexed { i, item ->
                    runCatching {
                        val bitmap = decodeForViewer(context, item.uri, 1024)
                        if (bitmap != null) {
                            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
                            for (face in faces) {
                                val crop = cropFace(bitmap, face.boundingBox)
                                if (crop != null) index.faces += FaceRecord(item.uri.toString(), embed(interpreter, crop))
                            }
                        }
                    }
                    index.scanned += item.uri.toString()
                    onProgress(i + 1, todo.size)
                }
            } finally {
                detector.close()
                interpreter.close()
                FaceStore.save(context, index)
            }
        }
    }

    private fun loadModel(context: Context): ByteBuffer {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    private fun cropFace(src: Bitmap, box: Rect): Bitmap? {
        val mx = (box.width() * CROP_MARGIN).toInt()
        val my = (box.height() * CROP_MARGIN).toInt()
        val l = (box.left - mx).coerceIn(0, src.width - 1)
        val t = (box.top - my).coerceIn(0, src.height - 1)
        val r = (box.right + mx).coerceIn(l + 1, src.width)
        val b = (box.bottom + my).coerceIn(t + 1, src.height)
        if (r - l < 8 || b - t < 8) return null
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(src, l, t, r - l, b - t), INPUT, INPUT, true)
    }

    /** Normalizes pixels to [-1, 1] as the model expects, then returns an L2-normalized embedding. */
    private fun embed(interpreter: Interpreter, face: Bitmap): FloatArray {
        val input = ByteBuffer.allocateDirect(4 * INPUT * INPUT * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT * INPUT)
        face.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        for (p in pixels) {
            input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        val dim = interpreter.getOutputTensor(0).shape()[1]
        val out = Array(1) { FloatArray(dim) }
        interpreter.run(input, out)
        return l2Normalize(out[0])
    }
}
