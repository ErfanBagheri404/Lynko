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
 *
 * The wire layer (httpPost/httpPostFile/stage/mimeOf) is frozen — the UI
 * state layer drives a [TransferSession] (LocalSend SessionStatus parity).
 */
object ShareSender {
    private const val TAG = "lynko-send"
    private const val READ_BUF = 64 * 1024
    private const val TIMEOUT_MS = 120_000L

    @Volatile var onChange: (() -> Unit)? = null
    private val started = AtomicBoolean(false)

    val session: TransferSession get() = Transfers.current
    val busy: Boolean
        get() = session.direction == "send" &&
            (session.status == SessionStatus.waiting || session.status == SessionStatus.sending)

    /** Full cancel URL for the live session (built at prepare-upload time). */
    @Volatile private var cancelUrl: String? = null

    /** Sender-initiated cancel: POST /cancel?sessionId → canceledBySender. */
    fun cancel() {
        val url = cancelUrl ?: return
        if (session.direction != "send") return
        session.status = SessionStatus.canceledBySender
        session.notifyChange()
        Thread({ runCatching { httpPost(url) } }, "lynko-send-cancel").start()
    }

    /** Per-file cancel (LocalSend's Skip button on a file row). The upload
     *  loop stops feeding bytes at the next 64 KB chunk and the desktop's own
     *  size/checksum check discards the truncated file. */
    fun skipFile(id: String) {
        val f = session.files.firstOrNull { it.id == id } ?: return
        if (f.status != FileStatus.queue && f.status != FileStatus.sending) return
        f.cancel = true
        session.notifyChange()
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

    /** Send one or more files (Share Sheet) to a desktop running a LocalSend v2.2 server. */
    fun send(
        context: Context,
        uris: List<Uri>,
        host: String,
        port: Int = 53317,
        pin: String? = null,
        displayNames: Map<String, String> = emptyMap(),
    ) {
        if (uris.isEmpty()) return
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val s = Transfers.start("send")
        s.onChange = { onChange?.invoke() }
        Thread({
            // Staged in the picker order, so the desktop lists the files the
            // way the user selected them. Staging reads every content:// URI
            // once and hashes it — that is where the "preparing" state lives.
            val staged = mutableListOf<Staged>()
            var sessionId: String? = null
            val alias = android.os.Build.MODEL.ifBlank { "Android" }
            val base = "http://$host:$port/api/localsend/v2"
            val pinArg = if (pin.isNullOrBlank()) "" else "&pin=$pin"
            try {
                for (u in uris) {
                    val st = stage(app, u) ?: error("Cannot open selected file")
                    // file:// URIs (Text/Paste temp files) carry no display
                    // name through the resolver — override without touching
                    // the frozen stage() itself.
                    val dn = displayNames[u.toString()]
                    staged.add(if (dn != null) st.copy(name = dn, mime = mimeOf(dn)) else st)
                }
                s.alias = alias
                s.files = staged.map { TransferFile(it.id, it.name, it.size) }
                s.sessionTotal = staged.sumOf { it.size }
                s.status = SessionStatus.waiting
                s.notifyChange()
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
                val filesObj = JSONObject()
                staged.forEach { st ->
                    filesObj.put(st.id, JSONObject()
                        .put("id", st.id)
                        .put("fileName", st.name)
                        .put("size", st.size)
                        .put("fileType", st.mime)
                        .put("sha256", st.sha256))
                }

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
                    // Nothing to transfer per spec — treat as finished.
                    s.finish()
                    return@Thread
                }
                if (code == 403) { s.status = SessionStatus.declined; s.notifyChange(); return@Thread }
                if (code == 409) { s.status = SessionStatus.recipientBusy; s.notifyChange(); return@Thread }
                if (code == 401) { s.status = SessionStatus.tooManyAttempts; s.notifyChange(); return@Thread }
                if (code != 200) error("prepare failed: $code $body")
                val resp = JSONObject(body)
                sessionId = resp.getString("sessionId")
                s.sessionId = sessionId
                s.status = SessionStatus.sending
                s.notifyChange()
                cancelUrl = "$base/cancel?sessionId=$sessionId$pinArg"
                val tokens = resp.getJSONObject("files")

                // POST /upload?sessionId&fileId&token — one binary POST per file
                // (spec 4.2). The desktop drops each file into Downloads as it
                // lands, so a failure halfway still leaves the earlier files.
                val total = s.sessionTotal
                var baseWritten = 0L
                for (i in staged.indices) {
                    if (s.status == SessionStatus.canceledBySender) break
                    val st = staged[i]
                    val tf = s.files.firstOrNull { it.id == st.id }
                    tf?.status = FileStatus.sending
                    val token = tokens.getString(st.id)
                    val (c, b) = httpPostFile(
                        "$base/upload?sessionId=$sessionId&fileId=${st.id}&token=$token",
                        st.file, st.size,
                    ) { written ->
                        tf?.written = written
                        s.onBytes(baseWritten + written, total)
                        // Per-file skip: stop streaming mid-file. Returning false
                        // closes the POST early; the receiver's size check throws
                        // the partial file away.
                        tf?.cancel != true
                    }
                    if (tf?.cancel == true) {
                        tf?.status = FileStatus.skipped
                        s.notifyChange()
                        continue
                    }
                    if (c == 422) {
                        // 422 checksum mismatch → per-file Error (LocalSend).
                        tf?.status = FileStatus.failed
                        s.notifyChange()
                        break
                    }
                    if (c != 200) error("upload failed: $c $b")
                    tf?.status = FileStatus.finished
                    tf?.written = st.size
                    baseWritten += st.size
                    s.onBytes(baseWritten, total)
                }
                if (s.status != SessionStatus.canceledBySender) {
                    // History: record every file the desktop actually saved.
                    if (s.errorCount == 0 && s.status != SessionStatus.finishedWithErrors) {
                        s.files.forEach { h ->
                            if (h.status == FileStatus.finished)
                                HistoryStore.add(app, h.name, h.size, incoming = false)
                        }
                    }
                    s.finish()
                }
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
                if (s.status != SessionStatus.canceledBySender &&
                    s.status != SessionStatus.declined &&
                    s.status != SessionStatus.recipientBusy &&
                    s.status != SessionStatus.tooManyAttempts
                ) {
                    s.files.forEach { if (it.status != FileStatus.finished) it.status = FileStatus.failed }
                    s.status = SessionStatus.finishedWithErrors
                    s.notifyChange()
                }
                val sid = sessionId
                if (sid != null) runCatching { httpPost("$base/cancel?sessionId=$sid$pinArg") }
            } finally {
                // Staged cache copies are scratch — drop them all, sent or not,
                // so a cancelled batch never fills the phone's cache.
                staged.forEach { runCatching { it.file.delete() } }
                cancelUrl = null
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
    private fun httpPostFile(url: String, file: java.io.File, total: Long, onChunk: (Long) -> Boolean): Pair<Int, String> {
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
                    if (!onChunk(written)) break
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
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
