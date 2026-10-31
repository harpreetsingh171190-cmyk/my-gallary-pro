package com.mygallery.app.pro

import android.content.Context
import android.graphics.Bitmap

/** Optional AI integration point. No cloud uploads; OCR/model runtime must be added separately. */
interface OfflineAi {
    suspend fun recognizeText(bitmap:Bitmap):String
    suspend fun labelImage(bitmap:Bitmap):List<String>
}
