package com.lynko.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Files this device is offering via the LocalSend download API (spec 5).
 * The desktop (or any browser) can GET /api/localsend/v2/download?fileId=.
 */
object Outbox {
    data class Entry(
        val id: String,
        val name: String,
        val size: Long,
        val fileType: String,
        val sha256: String,
        val path: String,
    )

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun files(): List<Entry> = entries.values.toList()

    fun get(id: String): Entry? = entries[id]

    fun clear() = entries.clear()

    /** Stage a content:// URI into cache so the HTTP server can stream it. */
    fun stage(ctx: Context, uri: Uri): Entry? {
        var name = "file.bin"
        var size = -1L
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }

        val safe = name.replace(Regex("[/\\\\]"), "_").ifBlank { "file.bin" }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val dst = File(ctx.cacheDir, "lynko-out-$id")
        val digest = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
        } ?: return null

        val entry = Entry(
            id = id,
            name = safe,
            size = if (size > 0) size else dst.length(),
            fileType = ctx.contentResolver.getType(uri) ?: "application/octet-stream",
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            path = dst.absolutePath,
        )
        entries[id] = entry
        return entry
    }
}
