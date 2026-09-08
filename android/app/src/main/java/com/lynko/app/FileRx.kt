package com.lynko.app

import android.content.Context
import android.util.Log
import java.io.File

object FileRx {
    private val buffers = mutableMapOf<String, ByteArray>()

    fun writeChunk(ctx: Context, id: String, data: ByteArray) {
        buffers.getOrPut(id) { ByteArray(0) }
        val current = buffers[id]!!
        buffers[id] = current + data
        Log.d("lynko", "file chunk $id: +${data.size} = ${buffers[id]!!.size}")
    }

    fun finalize(ctx: Context, id: String): File? {
        val data = buffers.remove(id) ?: return null
        val dir = File(ctx.filesDir, "lynko-files")
        dir.mkdirs()
        val file = File(dir, id)
        file.writeBytes(data)
        Log.i("lynko", "file saved: ${file.absolutePath} (${data.size} bytes)")
        return file
    }
}
