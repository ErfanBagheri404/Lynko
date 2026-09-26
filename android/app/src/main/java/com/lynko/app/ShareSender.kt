package com.lynko.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LocalSend Protocol v2.2 sender (spec 4.1–4.3).
 *
 * POST /api/localsend/v2/prepare-upload  → {sessionId, files:{id:token}}
 * POST /api/localsend/v2/upload?sessionId&fileId&token  (binary)
 * POST /api/localsend/v2/cancel?sessionId
 */
object ShareSender {
    private const val TAG = "lynko-send"
    private const val READ_BUF = 64 * 1024
    private const val TIMEOUT_MS = 120_000L

    @Volatile var status = ""
        private set
    @Volatile var busy = false
        private set
    @Volatile var onChange: (() -> Unit)? = null
    private val started = AtomicBoolean(false)

    private fun state(key: String, detail: String = "") {
        status = Loc.t("phone", key) + if (detail.isEmpty()) "" else "\n$detail"
        android.os.Handler(android.os.Looper.getMainLooper()).post { onChange?.invoke() }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"; "webp" -> "image/webp"
            "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"; "pdf" -> "application/pdf"
            "zip" -> "application/zip"; "txt", "log", "md" -> "text/plain"
            "json" -> "application/json"; "csv" -> "text/csv"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
    /** Send one file to a desktop running a LocalSend v2.2 server. */
    fun send(context: Context, uri: Uri, host: String, port: Int = 53317, pin: String? = null) {
        if (!started.compareAndSet(false, true)) return
        busy = true
        val app = context.applicationContext
        state("share_preparing")
        Thread({
            var staged: Staged? = null
            var sessionId: String? = null
            val alias = android.os.Build.MODEL.ifBlank { "Android" }
            val base = "http://$host:$port/api/localsend/v2"
            val pinArg = if (pin.isNullOrBlank()) "" else "&pin=$pin"
            try {
                staged = stage(app, uri) ?: error("Cannot open selected file")
                state("share_waiting", staged.name)
                val st = staged
                val info = JSONObject()
                    .put("alias", alias)
                    .put("version", "2.2")
                    .put("deviceModel", alias)
                    .put("deviceType", "mobile")
                    .put("fingerprint", "lynko-" + (android.provider.Settings.Secure.getString(
                        app.contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    ) ?: "").takeLast(16))
                    .put("port", port)
                    .put("protocol", "http")
                    .put("download", false)
                val filesObj = JSONObject().put(st.id, JSONObject()
                    .put("id", st.id)
                    .put("fileName", st.name)
                    .put("size", st.size)
                    .put("fileType", st.mime)
                    .put("sha256", st.sha256))

                // POST /prepare-upload → {sessionId, files:{id:token}}
                val manifest = JSONObject()
                    .put("info", info)
                    .put("files", filesObj)
                val (code, body) = httpPost(
                    "$base/prepare-upload?pin=$pinArg",
                    manifest.toString().toByteArray(),
                    "application/json",
                )
                if (code == 204) {
                    state("share_sent", staged.name)
                    return@Thread
                }
                if (code != 200) error("prepare failed: $code $body")
                val resp = JSONObject(body)
                sessionId = resp.getString("sessionId")
                val tokens = resp.getJSONObject("files")
                val token = tokens.getString(st.id)
                state("share_sending", staged.name)

                // POST /upload?sessionId&fileId&token — whole file as ONE binary POST (spec 4.2).
                val st2 = staged
                val (c, b) = httpPostFile(
                    "$base/upload?sessionId=$sessionId&fileId=${st2.id}&token=$token",
                    st2.file, st2.size,
                ) { written -> state("share_sending", "${st2.name} · $written / ${st2.size}") }
                if (c == 422) error("checksum mismatch")
                if (c != 200) error("upload failed: $c $b")
                state("share_sent", staged.name)
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
                val sid = sessionId
                if (sid != null) runCatching { httpPost("$base/cancel?sessionId=$sid$pinArg") }
                state("share_failed", e.message ?: "Transfer failed")
            } finally {
                busy = false
                started.set(false)
                android.os.Handler(android.os.Looper.getMainLooper()).post { onChange?.invoke() }
            }
        }, "lynko-file-send").start()
    }

    private fun httpPost(url: String, body: ByteArray? = null, contentType: String = "application/json"): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = TIMEOUT_MS.toInt()
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val text = try {
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        conn.disconnect()
        return code to text
    }

    /** Stream a whole file as one binary POST with a fixed Content-Length. */
    private fun httpPostFile(url: String, file: java.io.File, total: Long, onChunk: (Long) -> Unit): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = TIMEOUT_MS.toInt()
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(total)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        FileInputStream(file).use { input ->
            conn.outputStream.use { out ->
                val buf = ByteArray(READ_BUF)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    onChunk(written)
                }
            }
        }
        val code = conn.responseCode
        val text = try {
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        conn.disconnect()
        return code to text
    }

    /** Stage a content:// URI to cache, computing sha256 like the receiver. */
    private fun stage(ctx: Context, uri: Uri): Staged? {
        var name = "file.bin"
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
            }
        }
        val safe = name.replace(Regex("[/\\\\]"), "_").ifBlank { "file.bin" }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val dst = File(ctx.cacheDir, "lynko-send-" + id)
        val digest = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out ->
                val buf = ByteArray(READ_BUF)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
        } ?: return null
        return Staged(
            id = id,
            name = safe,
            size = dst.length(),
            mime = mimeOf(safe),
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            file = dst,
        )
    }

    /** One staged upload candidate. */
    data class Staged(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        val sha256: String,
        val file: File,
    )
}
