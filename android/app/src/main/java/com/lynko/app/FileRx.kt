package com.lynko.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** Receives file chunks (desktop -> phone). `begin` records the transfer,
 * chunks accumulate, `finalize` flushes to Downloads (MediaStore on Q+). */
object FileRx {
    private val buffers = mutableMapOf<String, ByteArray>()
    private val names = mutableMapOf<String, String>()

    /** Record an incoming transfer; name is the original desktop filename. */
    @Synchronized
    fun begin(id: String, name: String) {
        buffers[id] = ByteArray(0)
        names[id] = name.ifBlank { "file.bin" }
        Log.i("lynko", "file begin $id: ${names[id]}")
    }

    @Synchronized
    fun writeChunk(ctx: Context, id: String, data: ByteArray) {
        if (!buffers.containsKey(id)) begin(id, "file.bin") // begin lost? recover
        buffers[id] = buffers[id]!! + data
        Log.d("lynko", "file chunk $id: +${data.size} = ${buffers[id]!!.size}")
    }

    /** Flush the accumulated buffer to disk. Returns the file path or null. */
    @Synchronized
    fun finalize(ctx: Context, id: String): String? {
        val data = buffers.remove(id) ?: return null
        val name = names.remove(id) ?: "file.bin"
        val safe = name.replace(Regex("[/\\\\]"), "_")
        return try {
            val path: String = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(data) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Environment.DIRECTORY_DOWNLOADS + "/" + safe
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "")
                dir.mkdirs()
                val file = File(dir, safe)
                file.writeBytes(data)
                file.absolutePath
            }
            Log.i("lynko", "file saved: $path (${data.size} bytes)")
            path
        } catch (e: Exception) {
            Log.e("lynko", "file save failed", e)
            // Fallback: app-private storage always works
            val dir = File(ctx.filesDir, "lynko-files")
            dir.mkdirs()
            val file = File(dir, safe)
            file.writeBytes(data)
            Log.i("lynko", "file saved (app dir): ${file.absolutePath}")
            file.absolutePath
        }
    }

    /**
     * Save an already-written temp file (LocalSend-style transfer path) into
     * Downloads with the given display name. Deletes the temp afterwards.
     */
    @Synchronized
    fun saveStream(ctx: Context, tmp: File, name: String): String? {
        val safe = name.replace(Regex("[/\\\\]"), "_").ifBlank { "file.bin" }
        return try {
            val path: String = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Environment.DIRECTORY_DOWNLOADS + "/" + safe
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "")
                dir.mkdirs()
                val file = File(dir, safe)
                tmp.copyTo(file, overwrite = true)
                file.absolutePath
            }
            tmp.delete()
            Log.i("lynko", "file saved (stream): $path")
            path
        } catch (e: Exception) {
            Log.e("lynko", "stream save failed", e)
            null
        }
    }
}
