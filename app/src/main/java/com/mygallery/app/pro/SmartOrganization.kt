package com.mygallery.app.pro

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mygallery.app.GalleryMedia
import com.mygallery.app.MediaRepository
import kotlin.math.abs

object SmartOrganization {
    fun exactDuplicates(context:Context,items:List<GalleryMedia>)=MediaRepository.duplicates(context,items)
    /** Fast perceptual difference-hash for similar-image suggestions; verify before deleting. */
    fun differenceHash(bitmap:Bitmap):Long {
        val small=Bitmap.createScaledBitmap(bitmap,9,8,true);var hash=0L
        for(y in 0..7) for(x in 0..7) {
            fun gray(c:Int):Int = (android.graphics.Color.red(c)*30+android.graphics.Color.green(c)*59+android.graphics.Color.blue(c)*11)/100
            if(gray(small.getPixel(x,y))>gray(small.getPixel(x+1,y))) hash=hash or (1L shl (y*8+x))
        }
        if(small!==bitmap)small.recycle();return hash
    }
    fun similar(a:Long,b:Long,maxDistance:Int=8)=java.lang.Long.bitCount(a xor b)<=maxDistance
}
