package com.mygallery.app.pro

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri

object DocumentStudio {
    /** Uses Android's document picker; image capture is delegated to system camera UI. */
    fun pickDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "image/*"
        addCategory(Intent.CATEGORY_OPENABLE)
    }

    /** Existing list-based API retained for callers that already have all page bitmaps. */
    fun exportPdf(context: Context, pages: List<Bitmap>, destination: Uri): Boolean =
        exportPdf(context, pages.size, destination, { index -> pages[index] })

    /**
     * Writes pages one at a time. [bitmapAt] is invoked on the calling thread for each page;
     * [releaseBitmap] runs after that page has been drawn, allowing generated page images to be
     * disposed without retaining a second full document's worth of bitmaps in memory.
     */
    fun exportPdf(
        context: Context,
        pageCount: Int,
        destination: Uri,
        bitmapAt: (Int) -> Bitmap,
        releaseBitmap: (Bitmap) -> Unit = {}
    ): Boolean = runCatching {
        require(pageCount > 0)
        val pdf = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val bitmap = bitmapAt(index)
                try {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val scale = minOf(595f / bitmap.width, 842f / bitmap.height)
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val destinationRect = RectF((595f - width) / 2f, (842f - height) / 2f, (595f + width) / 2f, (842f + height) / 2f)
                    page.canvas.drawBitmap(bitmap, null, destinationRect, null)
                    pdf.finishPage(page)
                } finally {
                    releaseBitmap(bitmap)
                }
            }
            val output = context.contentResolver.openOutputStream(destination) ?: error("Could not open PDF destination")
            output.use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        true
    }.getOrDefault(false)
}
