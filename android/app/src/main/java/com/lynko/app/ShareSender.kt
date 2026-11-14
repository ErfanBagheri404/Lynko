package com.lynko.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LocalSend Protocol v2.2 sender (spec 4.1–4.3) + NectarLink-style resume and
 * offline queue.
 *
 * POST /api/localsend/v2/prepare-upload  → {sessionId, files:{id:token|{token,offset}}}
 * POST /api/localsend/v2/upload?sessionId&fileId&token[&offset=N]  (binary)
 * POST /api/localsend/v2/cancel?sessionId
 *
 * The wire layer (httpPost/httpPostFile/stage/mimeOf) is frozen — the UI
 * state layer drives a [TransferSession] (LocalSend SessionStatus parity).
 * Offline/unreachable batches land in [SendQueue] and retry on link-up.
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
        Thread({
            try {
                // Staged in the picker order, so the desktop lists the files the
                // way the user selected them. Staging reads every content:// URI
                // once and hashes it — that is where the "preparing" state lives.
                val staged = mutableListOf<Staged>()
                for (u in uris) {
                    val st = stage(app, u) ?: error("Cannot open selected file")
                    // file:// URIs (Text/Paste temp files) carry no display
                    // name through the resolver — override without touching
                    // the frozen stage() itself.
                    val dn = displayNames[u.toString()]
                    staged.add(if (dn != null) st.copy(name = dn, mime = mimeOf(dn)) else st)
                }
                runBatch(
                    app, staged, host, port, pin,
                    queueReason = if (PhoneState.link) null else "err_queue_offline",
                )
            } catch (e: Exception) {
                // Staging itself died (unreadable URI) — surface the terminal
                // card so the send lock releases with visible feedback.
                Log.w(TAG, "stage failed: ${e.message}")
                val s = Transfers.start("send")
                s.onChange = { onChange?.invoke() }
                s.status = SessionStatus.finishedWithErrors
                s.notifyChange()
            } finally {
                cancelUrl = null
                Handler(Looper.getMainLooper()).post { onChange?.invoke() }
            }
        }, "lynko-file-send").start()
    }

    /**
     * Re-send the offline queue once the link is back (SendQueue's watcher
     * calls this; anything else may too). Every snapshot is revalidated
     * against the size+mtime recorded at queue time: stale/missing ones FAIL
     * the file with an err_queue_* reason instead of sending stale bytes,
     * good ones go back through the regular send pipeline. The queue is
     * single-desktop (the phone only ever has one desktop linked), so all
     * entries retry to the host that is linked right now.
     */
    fun retryQueued(context: Context): Boolean {
        val app = context.applicationContext
        if (!PhoneState.link) return false
        val entries = SendQueue.list(app)
        if (entries.isEmpty()) return false
        if (!started.compareAndSet(false, true)) return false
        val host = LinkService.instance?.sharePeers()?.firstOrNull()
            ?.remoteSocketAddress?.address?.hostAddress ?: entries.first().host
        Thread({
            try {
                val stale = mutableListOf<Pair<SendQueue.Entry, String>>()
                val good = mutableListOf<SendQueue.Entry>()
                entries.forEach { e ->
                    val why = SendQueue.validate(app, e)
                    if (why.isEmpty()) good.add(e) else stale.add(e to why)
                }
                stale.forEach { SendQueue.drop(app, it.first.id) }
                val preFailed = stale.map { (e, why) ->
                    TransferFile(e.id, e.name, e.size).apply {
                        status = FileStatus.failed; failReason = why
                    }
                }
                if (good.isEmpty()) {
                    if (preFailed.isNotEmpty()) {
                        val s = Transfers.start("send")
                        s.onChange = { onChange?.invoke() }
                        s.files = preFailed
                        s.status = SessionStatus.finishedWithErrors
                        s.notifyChange()
                    }
                    return@Thread
                }
                runBatch(
                    app, good.map { it.toStaged(app) }, host, entries.first().port,
                    // A PIN may have been set/changed since the queue froze the
                    // old one — the fresh preference wins.
                    PinStore.current(app) ?: entries.first().pin.ifEmpty { null },
                    preFailed = preFailed,
                )
            } finally {
                cancelUrl = null
                started.set(false)
                Handler(Looper.getMainLooper()).post { onChange?.invoke() }
            }
        }, "lynko-send-retry").start()
        return true
    }

    /**
     * The whole send pipeline for one batch: session card, advertise manifest,
     * prepare-upload, one upload per file (resuming at the receiver's offset
     * when it reports one). Never throws — link-level failures fall through to
     * the [SendQueue] instead of losing the files.
     */
    private fun runBatch(
        app: Context,
        staged: List<Staged>,
        host: String,
        port: Int,
        pin: String?,
        queueReason: String? = null,
        preFailed: List<TransferFile> = emptyList(),
    ) {
        val s = Transfers.start("send")
        s.onChange = { onChange?.invoke() }
        var sessionId: String? = null
        val alias = android.os.Build.MODEL.ifBlank { "Android" }
        val base = "http://$host:$port/api/localsend/v2"
        val pinArg = if (pin.isNullOrBlank()) "" else "&pin=$pin"
        val queuedIds = mutableSetOf<String>()
        try {
            s.alias = alias
            s.files = preFailed + staged.map { TransferFile(it.id, it.name, it.size) }
            s.sessionTotal = staged.sumOf { it.size }
            s.status = SessionStatus.waiting
            s.notifyChange()

            if (queueReason != null) {
                // No desktop to send to — persist and show the queue rows.
                queueRest(app, s, staged, queueReason, host, port, pin, queuedIds)
                s.status = SessionStatus.finishedWithErrors
                s.notifyChange()
                return
            }

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
                    .put("length", st.size)
                    .put("fileType", st.mime)
                    .put("sha256", st.sha256))
            }

            // POST /prepare-upload → {sessionId, files:{id:token}}. With the
            // resume extension each entry may instead be {token, offset}: how
            // many bytes of that file the receiver already holds in its
            // staging copy. A plain string token (older receiver) means no
            // resume support — we start at 0 (cross-version safety).
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
                return
            }
            if (code == 403) { s.status = SessionStatus.declined; s.notifyChange(); return }
            if (code == 409) { s.status = SessionStatus.recipientBusy; s.notifyChange(); return }
            if (code == 401) { s.status = SessionStatus.tooManyAttempts; s.notifyChange(); return }
            if (code != 200) error("prepare failed: $code $body")
            val resp = JSONObject(body)
            sessionId = resp.getString("sessionId")
            s.sessionId = sessionId
            s.status = SessionStatus.sending
            s.notifyChange()
            cancelUrl = "$base/cancel?sessionId=$sessionId$pinArg"
            val tokens = resp.getJSONObject("files")
            // Receiver-side resume map (additive Lynko extension): the desktop
            // reports per-file bytes held at top level; a transfer-aware
            // sender streams only file[have..]. String token → offset 0.
            val haveObj = resp.optJSONObject("have")

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
                val node = tokens.opt(st.id)
                val token = when (node) {
                    is JSONObject -> node.optString("token")
                    else -> tokens.optString(st.id)
                }
                // Resume (NectarLink parity): the receiver is authoritative
                // about the bytes it already holds — stream the remainder
                // only. The offset query param tells it to append to its
                // .part copy instead of overwriting; an offset equal to size
                // sends the zero-length "file-done" body. An offset outside
                // 0..size is nonsense — restart from 0.
                val raw = when {
                    node is JSONObject -> node.optLong("offset", 0L)
                    haveObj != null -> haveObj.optLong(st.id, 0L)
                    else -> 0L
                }
                val from = if (raw in 0..st.size) raw else 0L
                val (c, b) = httpPostFile(
                    "$base/upload?sessionId=$sessionId&fileId=${st.id}&token=$token" +
                        (if (from > 0) "&offset=$from" else ""),
                    st.file, st.size, from,
                ) { written ->
                    tf?.written = written + from
                    s.onBytes(baseWritten + from + written, total)
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
                SendQueue.drop(app, st.id)   // retried queue item that just landed
                HistoryStore.add(app, tf?.name ?: st.name, st.size, incoming = false)
            }
            if (s.status != SessionStatus.canceledBySender) s.finish()
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            val terminal = s.status == SessionStatus.canceledBySender ||
                s.status == SessionStatus.declined ||
                s.status == SessionStatus.recipientBusy ||
                s.status == SessionStatus.tooManyAttempts
            if (!terminal) {
                // Link-level causes (connect/socket/EOF exceptions, or the WS
                // link is already gone) queue the remaining files; real
                // protocol answers (server error, wrong PIN) stay terminal.
                val queueable = e is java.io.IOException || !PhoneState.link
                if (queueable) {
                    queueRest(
                        app, s, staged,
                        if (PhoneState.link) "err_queue_link_lost" else "err_queue_offline",
                        host, port, pin, queuedIds,
                    )
                }
                s.files.forEach {
                    if ((it.status == FileStatus.queue || it.status == FileStatus.sending) && !it.queued)
                        it.status = FileStatus.failed
                }
                s.status = SessionStatus.finishedWithErrors
                s.notifyChange()
            }
            val sid = sessionId
            if (sid != null) runCatching { httpPost("$base/cancel?sessionId=$sid$pinArg") }
        } finally {
            // Staged scratch copies are dropped unless they were moved into
            // the SendQueue — sent or not, a cancelled batch never fills the
            // phone's cache.
            staged.forEach { if (it.id !in queuedIds) runCatching { it.file.delete() } }
            cancelUrl = null
            Handler(Looper.getMainLooper()).post { onChange?.invoke() }
        }
    }

    /** Persist every pending/in-flight staged file into the offline queue. */
    private fun queueRest(
        app: Context,
        s: TransferSession,
        staged: List<Staged>,
        reason: String,
        host: String,
        port: Int,
        pin: String?,
        queuedIds: MutableSet<String>,
    ) {
        staged.forEach { st ->
            val tf = s.files.firstOrNull { it.id == st.id } ?: return@forEach
            if (tf.status != FileStatus.queue && tf.status != FileStatus.sending) return@forEach
            // A user skip is a decision, not a transient failure — never
            // resurrect it through the queue.
            if (tf.cancel) { tf.status = FileStatus.skipped; return@forEach }
            val e = SendQueue.persist(app, st, host, port, pin)
            if (e == null) {
                tf.status = FileStatus.failed
                tf.failReason = "err_queue_missing"
            } else {
                tf.status = FileStatus.queue
                tf.queued = true
                tf.failReason = reason
                queuedIds.add(st.id)
            }
        }
        s.notifyChange()
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

    /** Stream a file as one binary POST with a fixed Content-Length, starting
     *  at [from] (the receiver's resume offset; 0 for a fresh send). */
    private fun httpPostFile(
        url: String,
        file: File,
        total: Long,
        from: Long = 0L,
        onChunk: (Long) -> Boolean,
    ): Pair<Int, String> {
        val length = (total - from).coerceAtLeast(0L)
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = TIMEOUT_MS.toInt()
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(length)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        FileInputStream(file).use { input ->
            if (from > 0) input.channel.position(from)
            conn.outputStream.use { out ->
                val buf = ByteArray(READ_BUF)
                var written = 0L
                while (written < length) {
                    if (!onChunk(written)) break
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - written).toInt())
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
