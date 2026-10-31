package com.mygallery.app.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment

object MediaConverter {
    /** Safe WebP format on every supported API level (WEBP_LOSSY/LOSSLESS exist only on API 30+; minSdk is 29). */
    @Suppress("DEPRECATION")
    fun webp(lossless:Boolean=false):Bitmap.CompressFormat =
        if(android.os.Build.VERSION.SDK_INT>=30) (if(lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY) else Bitmap.CompressFormat.WEBP
    fun resize(bitmap: Bitmap, width: Int, height: Int): Bitmap = Bitmap.createScaledBitmap(bitmap,width,height,true)
    fun export(context:Context, bitmap:Bitmap, name:String, format:Bitmap.CompressFormat=Bitmap.CompressFormat.JPEG, quality:Int=85):Uri? {
        val mime=when(format) { Bitmap.CompressFormat.PNG->"image/png"; Bitmap.CompressFormat.JPEG->"image/jpeg"; else->"image/webp" }
        val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,mime);put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/MyGallery");put(MediaStore.Images.Media.IS_PENDING,1) }
        val resolver=context.contentResolver;val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:return null
        return try { resolver.openOutputStream(uri)!!.use { check(bitmap.compress(format,quality.coerceIn(0,100),it)) };values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);resolver.update(uri,values,null,null);uri }
        catch(e:Exception) { resolver.delete(uri,null,null);null }
    }
    fun decode(context:Context,uri:Uri,maxDimension:Int=2048):Bitmap? = context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes=stream.readBytes();val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        var sample=1;while(opts.outWidth/sample>maxDimension || opts.outHeight/sample>maxDimension) sample*=2
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
    }
}
