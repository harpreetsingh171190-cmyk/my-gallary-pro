package com.mygallery.app.pro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.*

object ProPhotoStudio {
    /** Pixel-level HSL edit; output is a copy. For large images use downsampled previews. */
    fun hsl(input: Bitmap, hueShift: Float, saturation: Float, lightness: Float): Bitmap {
        val src = input.copy(Bitmap.Config.ARGB_8888, false) ?: return input
        val pixels = IntArray(src.width*src.height)
        src.getPixels(pixels,0,src.width,0,0,src.width,src.height)
        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val alpha = Color.alpha(pixels[i]); Color.colorToHSV(pixels[i],hsv)
            hsv[0] = ((hsv[0] + hueShift) % 360f + 360f) % 360f
            hsv[1] = (hsv[1]*saturation).coerceIn(0f,1f)
            hsv[2] = (hsv[2]+lightness).coerceIn(0f,1f)
            pixels[i] = Color.HSVToColor(alpha,hsv)
        }
        return Bitmap.createBitmap(pixels,src.width,src.height,Bitmap.Config.ARGB_8888)
    }
    /** LUT curve (256 values), applied per RGB channel. */
    fun curve(input: Bitmap, lut: IntArray): Bitmap {
        require(lut.size==256)
        val pixels=IntArray(input.width*input.height); input.getPixels(pixels,0,input.width,0,0,input.width,input.height)
        for(i in pixels.indices) { val c=pixels[i]; pixels[i]=Color.argb(Color.alpha(c),lut[Color.red(c)].coerceIn(0,255),lut[Color.green(c)].coerceIn(0,255),lut[Color.blue(c)].coerceIn(0,255)) }
        return Bitmap.createBitmap(pixels,input.width,input.height,Bitmap.Config.ARGB_8888)
    }
    /** Mask uses alpha to blend edited pixels with original pixels. */
    fun mask(original: Bitmap, edited: Bitmap, mask: Bitmap): Bitmap {
        require(original.width==edited.width && original.height==edited.height && original.width==mask.width && original.height==mask.height)
        val out=Bitmap.createBitmap(original.width,original.height,Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(out); canvas.drawBitmap(original,0f,0f,null)
        val layer=edited.copy(Bitmap.Config.ARGB_8888,true)
        val paint=android.graphics.Paint().apply { xfermode=android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
        android.graphics.Canvas(layer).drawBitmap(mask,0f,0f,paint)
        canvas.drawBitmap(layer,0f,0f,null); layer.recycle(); return out
    }
    fun batch(items: List<Bitmap>, hue:Float, saturation:Float):List<Bitmap> = items.map { hsl(it,hue,saturation,0f) }
}
