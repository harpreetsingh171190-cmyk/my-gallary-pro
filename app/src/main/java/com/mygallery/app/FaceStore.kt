package com.mygallery.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves the face index as JSON in app-private storage. Android does not back it up (allowBackup is
 * false), and it is not in the shared media folders. Only embeddings and photo URIs are stored, never
 * face images.
 */
object FaceStore {
    private fun file(context: Context) = File(context.filesDir, "faces/index.json")

    fun load(context: Context): FaceIndex = runCatching {
        val json = JSONObject(file(context).readText())
        val scanned = mutableSetOf<String>()
        val scannedArray = json.getJSONArray("scanned")
        for (i in 0 until scannedArray.length()) scanned += scannedArray.getString(i)
        val faces = mutableListOf<FaceRecord>()
        val faceArray = json.getJSONArray("faces")
        for (i in 0 until faceArray.length()) {
            val o = faceArray.getJSONObject(i)
            val e = o.getJSONArray("e")
            faces += FaceRecord(o.getString("uri"), FloatArray(e.length()) { e.getDouble(it).toFloat() })
        }
        FaceIndex(scanned, faces)
    }.getOrElse { FaceIndex() }

    fun save(context: Context, index: FaceIndex) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val faces = JSONArray()
        for (face in index.faces) {
            faces.put(JSONObject().put("uri", face.uri).put("e", JSONArray(face.embedding.map { it.toDouble() })))
        }
        val json = JSONObject()
            .put("scanned", JSONArray(index.scanned.toList()))
            .put("faces", faces)
        f.writeText(json.toString())
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
