package com.mygallery.app.pro

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer

object VideoStudio {
    /** Trim one clip and export to an app-owned file. Call from the main thread (Transformer needs a Looper thread). Caller must manage lifecycle and destination. */
    fun trim(context:Context,source:Uri,startMs:Long,endMs:Long,outputPath:String,listener:Transformer.Listener):Transformer {
        require(endMs>startMs && startMs>=0)
        val media=MediaItem.Builder().setUri(source).setClippingConfiguration(ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
        return Transformer.Builder(context).addListener(listener).build().also { it.start(EditedMediaItem.Builder(media).build(),outputPath) }
    }
    /** Multi-track transitions and audio mixing are roadmap items, not enabled in this build. */
}
