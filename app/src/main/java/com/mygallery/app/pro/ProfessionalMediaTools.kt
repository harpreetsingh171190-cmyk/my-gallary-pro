package com.mygallery.app.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.mygallery.app.GalleryMedia

object ProfessionalMediaTools {
    fun storageByFolder(items:List<GalleryMedia>):Map<String,Long> = items.groupBy { it.folder }.mapValues { (_,files)->files.sumOf { it.size } }
    fun watermark(source:Bitmap,label:String):Bitmap = source.copy(Bitmap.Config.ARGB_8888,true).also { bitmap ->
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=(bitmap.width/24f).coerceAtLeast(18f);setShadowLayer(3f,1f,1f,Color.BLACK) }
        Canvas(bitmap).drawText(label,20f,bitmap.height-30f,paint)
    }
    fun exif(context:Context,uri:Uri):Map<String,String> = SmartGallery.metadata(context,uri)
}
