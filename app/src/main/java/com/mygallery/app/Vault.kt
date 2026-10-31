package com.mygallery.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted app-private storage. Import copies the original; it does not delete it. */
object Vault {
    private const val ALIAS = "my_gallery_vault_v1"
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
    private val PAYLOAD_MAGIC = byteArrayOf('M'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte())
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    /** Playback stages authenticated plaintext only in app-private cache, and refuses larger items. */
    const val MAX_PREVIEW_BYTES: Long = 512L * 1024L * 1024L
    private const val PLAYBACK_PREFIX = "vault-playback-"
    private const val EXPORT_PREFIX = "vault-export-"

    private fun dir(context: Context) = File(context.filesDir, "vault").apply { mkdirs() }
    private fun privateTemp(context: Context, prefix: String, suffix: String): File {
        val cache = File(context.cacheDir, "vault-private").apply { mkdirs() }
        val file = File.createTempFile(prefix, suffix, cache)
        // cacheDir is app-private; additionally make the temporary file owner-only.
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        return file
    }

    data class Metadata(val fileName: String, val mimeType: String, val mediaType: String) {
        val isVideo: Boolean get() = mediaType == "video"
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    fun files(context: Context): List<File> = dir(context).listFiles { f -> f.isFile && f.extension == "vault" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun guessMime(name: String, isVideo: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            else -> if (isVideo) "video/mp4" else "image/jpeg"
        }
    }

    private fun sourceMetadata(context: Context, uri: Uri): Metadata {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        }
        val safeName = name?.takeIf { it.isNotBlank() } ?: "Vault_${System.currentTimeMillis()}"
        val reportedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val extensionIsVideo = safeName.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "3gp", "webm", "mkv", "mov", "avi")
        val usableMime = reportedMime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        val isVideo = usableMime?.startsWith("video/", ignoreCase = true) == true || extensionIsVideo
        val mime = if (isVideo && usableMime?.startsWith("video/", true) != true) guessMime(safeName, true)
        else usableMime ?: guessMime(safeName, isVideo)
        return Metadata(safeName, mime, if (isVideo) "video" else "image")
    }

    private fun encodeMetadata(metadata: Metadata): ByteArray {
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF(metadata.fileName); data.writeUTF(metadata.mimeType); data.writeUTF(metadata.mediaType)
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return try {
            ByteArrayOutputStream().use { bytes ->
                bytes.write(cipher.iv)
                bytes.write(cipher.doFinal(plain))
                bytes.toByteArray()
            }
        } finally { plain.fill(0) }
    }

    private fun decodeMetadata(blob: ByteArray): Metadata {
        require(blob.size >= 28) { "Invalid vault metadata" }
        val iv = blob.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val plain = cipher.doFinal(blob.copyOfRange(12, blob.size))
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { data ->
                val metadata = Metadata(data.readUTF(), data.readUTF(), data.readUTF())
                require(metadata.mediaType == "video" || metadata.mediaType == "image") { "Invalid vault item type" }
                metadata
            }
        } finally { plain.fill(0) }
    }

    /** Encrypts source bytes as a streaming copy. The authenticated header retains original name/MIME/type. */
    fun import(context: Context, uri: Uri): Boolean {
        val output = File(dir(context), "item_${UUID.randomUUID()}.vault")
        return try {
            val metadata = sourceMetadata(context, uri)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val metadataBlob = encodeMetadata(metadata)
            val headerBytes = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.write(MAGIC); data.writeByte(cipher.iv.size); data.write(cipher.iv)
                    data.writeInt(metadataBlob.size); data.write(metadataBlob)
                }
                bytes.toByteArray()
            }
            cipher.updateAAD(headerBytes)
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open source media")
            source.use { input ->
                output.outputStream().use { destination ->
                    destination.write(headerBytes)
                    val encrypted = javax.crypto.CipherOutputStream(destination, cipher)
                    encrypted.write(PAYLOAD_MAGIC)
                    input.copyTo(encrypted, STREAM_BUFFER_SIZE)
                    encrypted.close()
                }
            }
            true
        } catch (_: Exception) {
            output.delete()
            false
        }
    }

    private data class Decoded(val metadata: Metadata?, val bytes: ByteArray)

    /** Compatibility API; avoid for large media. Streaming export/playback use decryptTo instead. */
    private fun decryptItem(file: File): Decoded = file.inputStream().use { raw ->
        val first = ByteArray(MAGIC.size)
        DataInputStream(raw).readFully(first)
        if (first.contentEquals(MAGIC)) {
            val data = DataInputStream(raw)
            val ivSize = data.readUnsignedByte()
            require(ivSize == 12) { "Invalid vault file" }
            val iv = ByteArray(ivSize); data.readFully(iv)
            val metadataSize = data.readInt()
            require(metadataSize in 28..65536) { "Invalid vault metadata" }
            val metadataBlob = ByteArray(metadataSize); data.readFully(metadataBlob)
            val metadata = decodeMetadata(metadataBlob)
            val header = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.write(MAGIC); out.writeByte(ivSize); out.write(iv)
                    out.writeInt(metadataBlob.size); out.write(metadataBlob)
                }
                bytes.toByteArray()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(header)
            }
            val plaintext = cipher.doFinal(data.readBytes())
            require(plaintext.size >= PAYLOAD_MAGIC.size && plaintext.copyOfRange(0, PAYLOAD_MAGIC.size).contentEquals(PAYLOAD_MAGIC)) { "Invalid vault payload" }
            Decoded(metadata, plaintext.copyOfRange(PAYLOAD_MAGIC.size, plaintext.size).also { plaintext.fill(0) })
        } else {
            // Legacy image format: one-byte IV size, IV, then AES-GCM ciphertext.
            val ivSize = first[0].toInt() and 0xff
            require(ivSize == 12) { "Invalid vault file" }
            val iv = ByteArray(ivSize)
            val alreadyRead = minOf(ivSize, first.size - 1)
            System.arraycopy(first, 1, iv, 0, alreadyRead)
            var offset = alreadyRead
            while (offset < ivSize) {
                val n = raw.read(iv, offset, ivSize - offset)
                require(n > 0) { "Truncated vault file" }
                offset += n
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            Decoded(null, cipher.doFinal(raw.readBytes()))
        }
    }

    /** Returns media bytes for source compatibility. This can consume memory proportional to file size. */
    fun decrypt(@Suppress("UNUSED_PARAMETER") context: Context, file: File): ByteArray = decryptItem(file).bytes

    private fun isCurrentFormat(file: File): Boolean = file.inputStream().use { input ->
        val magic = ByteArray(MAGIC.size)
        val count = input.read(magic)
        count == magic.size && magic.contentEquals(MAGIC)
    }

    /** Stream decrypts and verifies the GCM tag before returning. Destination must remain private/pending until success. */
    private fun decryptTo(file: File, destination: OutputStream) {
        file.inputStream().use { raw ->
            val data = DataInputStream(raw)
            val first = ByteArray(MAGIC.size)
            data.readFully(first)
            val cipher: Cipher
            val currentFormat: Boolean
            if (first.contentEquals(MAGIC)) {
                currentFormat = true
                val ivSize = data.readUnsignedByte()
                require(ivSize == 12) { "Invalid vault file" }
                val iv = ByteArray(ivSize); data.readFully(iv)
                val metadataSize = data.readInt()
                require(metadataSize in 28..65536) { "Invalid vault metadata" }
                val metadataBlob = ByteArray(metadataSize); data.readFully(metadataBlob)
                decodeMetadata(metadataBlob)
                val header = ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.write(MAGIC); out.writeByte(ivSize); out.write(iv)
                        out.writeInt(metadataBlob.size); out.write(metadataBlob)
                    }
                    bytes.toByteArray()
                }
                cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(header)
                }
            } else {
                currentFormat = false
                // Original legacy image format: IV-size byte, 12-byte IV, then ciphertext.
                val ivSize = first[0].toInt() and 0xff
                require(ivSize == 12) { "Invalid vault file" }
                val iv = ByteArray(ivSize)
                val copied = minOf(ivSize, first.size - 1)
                System.arraycopy(first, 1, iv, 0, copied)
                var offset = copied
                while (offset < ivSize) {
                    val read = raw.read(iv, offset, ivSize - offset)
                    require(read > 0) { "Truncated vault file" }
                    offset += read
                }
                cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            }

            val encryptedBuffer = ByteArray(STREAM_BUFFER_SIZE)
            val prefix = ByteArray(PAYLOAD_MAGIC.size)
            var prefixSize = 0
            var prefixVerified = !currentFormat
            fun writePlain(bytes: ByteArray?) {
                if (bytes == null || bytes.isEmpty()) return
                if (!currentFormat) { destination.write(bytes); return }
                var start = 0
                if (!prefixVerified) {
                    val copy = minOf(PAYLOAD_MAGIC.size - prefixSize, bytes.size)
                    System.arraycopy(bytes, 0, prefix, prefixSize, copy)
                    prefixSize += copy
                    start += copy
                    if (prefixSize == PAYLOAD_MAGIC.size) {
                        require(prefix.contentEquals(PAYLOAD_MAGIC)) { "Invalid vault payload" }
                        prefixVerified = true
                    }
                }
                if (prefixVerified && start < bytes.size) destination.write(bytes, start, bytes.size - start)
            }
            while (true) {
                val count = raw.read(encryptedBuffer)
                if (count < 0) break
                if (count == 0) continue
                writePlain(cipher.update(encryptedBuffer, 0, count))
            }
            // doFinal authenticates GCM. Callers must not publish/retain output if this throws.
            writePlain(cipher.doFinal())
            require(prefixVerified) { "Invalid vault payload" }
            encryptedBuffer.fill(0); prefix.fill(0)
            destination.flush()
        }
    }

    private fun decryptToFile(file: File, target: File) {
        try { FileOutputStream(target).use { decryptTo(file, it) } }
        catch (failure: Throwable) { target.delete(); throw failure }
    }

    /** Creates a private, authenticated, bounded temporary video for MediaPlayer; delete it when playback ends. */
    fun prepareVideoPlayback(context: Context, file: File): File {
        require(file.length() <= MAX_PREVIEW_BYTES) { "Video exceeds the private preview limit" }
        cleanupPrivateTemps(context)
        val temporary = privateTemp(context, PLAYBACK_PREFIX, ".media")
        decryptToFile(file, temporary)
        return temporary
    }

    /** Best-effort removal of abandoned plaintext staging files after process death. */
    fun cleanupPrivateTemps(context: Context) {
        val cache = File(context.cacheDir, "vault-private")
        val staleBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        cache.listFiles()?.filter {
            (it.name.startsWith(PLAYBACK_PREFIX) || it.name.startsWith(EXPORT_PREFIX)) && it.lastModified() < staleBefore
        }
            ?.forEach { runCatching { it.delete() } }
    }

    /** Reads authenticated metadata without decrypting the media body; legacy images are staged privately for sniffing. */
    fun metadata(context: Context, file: File): Metadata {
        return try {
            file.inputStream().use { raw ->
                val magic = ByteArray(MAGIC.size)
                DataInputStream(raw).readFully(magic)
                if (magic.contentEquals(MAGIC)) {
                    val data = DataInputStream(raw)
                    val ivSize = data.readUnsignedByte()
                    require(ivSize == 12) { "Invalid vault file" }
                    val iv = ByteArray(ivSize); data.readFully(iv)
                    val metadataSize = data.readInt()
                    require(metadataSize in 28..65536) { "Invalid vault metadata" }
                    val metadataBlob = ByteArray(metadataSize); data.readFully(metadataBlob)
                    decodeMetadata(metadataBlob)
                } else {
                    val temporary = privateTemp(context, EXPORT_PREFIX, ".media")
                    try {
                        decryptToFile(file, temporary)
                        legacyMetadataFromFile(file, temporary)
                    } finally { temporary.delete() }
                }
            }
        } catch (_: Exception) {
            Metadata(file.nameWithoutExtension, "application/octet-stream", "image")
        }
    }

    private fun legacyMetadataFromFile(vaultFile: File, plaintextFile: File): Metadata {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(plaintextFile.absolutePath, bounds)
        val mime = bounds.outMimeType ?: "image/jpeg"
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/heic" -> "heic"; else -> "jpg" }
        return Metadata("${vaultFile.nameWithoutExtension}.$ext", mime, "image")
    }

    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Decrypts image data in memory and decodes a down-sampled, correctly oriented bitmap. */
    fun thumbnail(context: Context, file: File, maxDimension: Int): Bitmap? {
        val cacheKey = "${file.name}@$maxDimension"
        val cacheable = maxDimension <= 640
        if (cacheable) thumbCache.get(cacheKey)?.let { return it }
        return try {
            val item = decryptItem(file)
            if (item.metadata?.isVideo == true) { item.bytes.fill(0); return null }
            val bytes = item.bytes
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { bytes.fill(0); return null }
            var sample = 1
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: run { bytes.fill(0); return null }
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val result = PhotoEditor.applyOrientation(decoded, orientation)
            bytes.fill(0)
            if (cacheable) thumbCache.put(cacheKey, result)
            result
        } catch (_: Throwable) {
            null
        }
    }

    /** Streams authenticated plaintext into a pending MediaStore item; it is published only after GCM verification. */
    fun export(context: Context, file: File): Uri? {
        var inserted: Uri? = null
        var legacyTemp: File? = null
        return try {
            val legacy = !isCurrentFormat(file)
            val metadata: Metadata
            if (legacy) {
                legacyTemp = privateTemp(context, EXPORT_PREFIX, ".media")
                decryptToFile(file, legacyTemp!!)
                metadata = legacyMetadataFromFile(file, legacyTemp!!)
            } else metadata = metadata(context, file)
            val video = metadata.isVideo || metadata.mimeType.startsWith("video/", true)
            val resolver = context.contentResolver
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val relativePath = if (video) Environment.DIRECTORY_MOVIES + "/MyGallery" else Environment.DIRECTORY_PICTURES + "/MyGallery"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, metadata.fileName.ifBlank { "Vault_${System.currentTimeMillis()}" })
                put(MediaStore.MediaColumns.MIME_TYPE, metadata.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            inserted = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(inserted!!)?.use { output ->
                if (legacy) FileInputStream(legacyTemp!!).use { it.copyTo(output, STREAM_BUFFER_SIZE) }
                else decryptTo(file, output)
            } ?: throw IOException("No output stream")
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(inserted!!, values, null, null) > 0) { "Could not publish exported media" }
            inserted
        } catch (_: Throwable) {
            inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            null
        } finally { legacyTemp?.delete() }
    }

    fun delete(file: File): Boolean = file.delete()
}
