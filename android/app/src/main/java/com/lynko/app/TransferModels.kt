package com.lynko.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Session state mirroring LocalSend's SessionStatus (session_status.dart). */
enum class SessionStatus { waiting, recipientBusy, declined, tooManyAttempts,
    sending, finished, finishedWithErrors, canceledBySender, canceledByReceiver }

/** File state mirroring LocalSend's FileStatus (file_status.dart). */
enum class FileStatus { queue, skipped, sending, failed, finished }

data class TransferFile(
    val id: String,
    val name: String,
    val size: Long,
) {
    @Volatile var status: FileStatus = FileStatus.queue
    @Volatile var written: Long = 0L
    /** Per-file cancel (LocalSend's "Skip"): the current upload loop checks
     *  this before every chunk and finishes the POST early. The desktop saves
     *  the short file and then drops it (its size/checksum check fails), so a
     *  skipped file never lands half-written. */
    @Volatile var cancel: Boolean = false
    /** Offline-paused: the bytes are snapshotted in [SendQueue] and retried
     *  when the desktop link comes back (ShareSender.retryQueued). Rendered as
     *  the "Queue" row; status stays non-terminal, never regresses. */
    @Volatile var queued: Boolean = false
    /** Failure reason as a Loc key ("err_queue_changed", ...) or blank. */
    @Volatile var failReason: String = ""
}

/**
 * The one live file-transfer session (send OR receive). ShareSender drives
 * it for phone→desktop; TransferServer/FileRx drive it for desktop→phone.
 * Mutations are cheap and post [onChange] to the main looper so the UI can
 * repaint. [lastSession] holds the terminal session until the UI dismisses it.
 */
class TransferSession(val direction: String) { // "send" | "receive"
    @Volatile var status: SessionStatus = SessionStatus.waiting
    @Volatile var files: List<TransferFile> = emptyList()
    @Volatile var sessionWritten: Long = 0L
    @Volatile var sessionTotal: Long = 0L
    @Volatile var speedBps: Long = 0L
    @Volatile var etaSeconds: Long = 0L
    @Volatile var sessionId: String? = null
    @Volatile var alias: String = ""
    /** True from reset() until the UI dismisses an end state (Close button). */
    @Volatile var visible: Boolean = false

    val terminal: Boolean
        get() = status == SessionStatus.recipientBusy ||
            status == SessionStatus.declined ||
            status == SessionStatus.tooManyAttempts ||
            status == SessionStatus.finished ||
            status == SessionStatus.finishedWithErrors ||
            status == SessionStatus.canceledBySender ||
            status == SessionStatus.canceledByReceiver

    val finishedCount: Int get() = files.count { it.status == FileStatus.finished }
    val errorCount: Int get() = files.count { it.status == FileStatus.failed }

    @Volatile var onChange: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private var lastT: Long = System.currentTimeMillis()
    private var lastWritten: Long = 0L

    fun reset() {
        status = SessionStatus.waiting
        files = emptyList()
        sessionWritten = 0L; sessionTotal = 0L
        speedBps = 0L; etaSeconds = 0L; sessionId = null; alias = ""
        lastT = System.currentTimeMillis(); lastWritten = 0L
        notifyChange()
    }

    fun notifyChange() {
        val h = onChange
        main.post {
            try { h?.invoke(); Transfers.onAnyChange?.invoke() } catch (_: Exception) {}
        }
    }

    /** Called as bytes land; EMA over the last ~1s for the Speed row. */
    fun onBytes(written: Long, total: Long) {
        sessionWritten = written
        sessionTotal = total
        val now = System.currentTimeMillis()
        val dt = now - lastT
        if (dt >= 800L) {
            val inst = ((written - lastWritten) * 1000L) / dt
            speedBps = if (speedBps == 0L) inst else (speedBps * 7 + inst) / 8
            lastT = now; lastWritten = written
        }
        if (speedBps > 0L && total > written) etaSeconds = (total - written) / speedBps
        notifyChange()
    }

    fun finish() {
        if (errorCount > 0) status = SessionStatus.finishedWithErrors else status = SessionStatus.finished
        speedBps = 0L
        notifyChange()
    }
}

/**
 * Process-wide holder for the live transfer session. ShareSender (send) and
 * TransferServer/FileRx (receive) both call [start]; the UI reads [current]
 * and calls [dismiss] (Close) to hide a terminal state.
 */
object Transfers {
    @Volatile var current: TransferSession = TransferSession("send")
    /** Global repaint hook (ShareTabView) — fires for ANY session, even one
     *  created before the tab hooked that session instance. */
    @Volatile var onAnyChange: (() -> Unit)? = null

    fun start(direction: String): TransferSession {
        val s = TransferSession(direction)
        s.visible = true
        current = s
        s.notifyChange()
        return s
    }

    /** Hide a terminal session (progress panel Close). */
    fun dismiss() {
        current.visible = false
        current.notifyChange()
    }
}

/** Readable size in LocalSend's decimal style: n B / x.x KB / x.x MB / x.x GB. */
fun readableSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val kb = bytes / 1000.0
    if (kb < 1000) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1000.0
    if (mb < 1000) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1000.0)
}

/** m:ss or {h}h {m}m for the Total progress (eta) label. */
fun formatEta(sec: Long): String {
    if (sec <= 0) return ""
    val s = sec % 60
    val m = (sec / 60) % 60
    val h = sec / 3600
    return if (h > 0) "${h}h ${m}m" else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * Pinned destinations (LocalSend favorites): the desktop you send to most,
 * listed first instead of wherever mDNS happened to order it. Keyed by
 * [PeerInfo.keyOf] so it survives a rename of case/wording only.
 */
object Favorites {
    private fun file(ctx: android.content.Context): File = File(ctx.filesDir, "share_favorites.json")

    fun list(ctx: android.content.Context): Set<String> = runCatching {
        val f = file(ctx)
        if (!f.exists()) emptySet()
        else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }.toSet()
        }
    }.getOrDefault(emptySet())

    fun isFavorite(ctx: android.content.Context, peer: String): Boolean =
        PeerInfo.keyOf(peer) in list(ctx)

    /** Returns the new state, so the caller can announce it without re-reading. */
    fun toggle(ctx: android.content.Context, peer: String): Boolean {
        val key = PeerInfo.keyOf(peer)
        val cur = list(ctx).toMutableSet()
        val on = if (!cur.add(key)) { cur.remove(key); false } else true
        runCatching { file(ctx).writeText(JSONArray(cur.toList()).toString()) }
        return on
    }
}

/**
 * Persistent transfer history. JSON array at filesDir/transfer_history.json.
 * add()/list()/delete()/clear(). Records every finished save, both directions.
 */
object HistoryStore {
    private fun file(ctx: android.content.Context): File =
        File(ctx.filesDir, "transfer_history.json")

    fun add(ctx: android.content.Context, fileName: String, size: Long, incoming: Boolean) {
        val arr = list(ctx)
        val e = JSONObject()
            .put("id", java.util.UUID.randomUUID().toString())
            .put("name", fileName)
            .put("size", size)
            .put("time", System.currentTimeMillis())
            .put("incoming", incoming)
        arr.put(e)
        write(ctx, arr)
    }

    fun list(ctx: android.content.Context): JSONArray {
        val f = file(ctx)
        if (!f.exists()) return JSONArray()
        return try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
    }

    fun delete(ctx: android.content.Context, id: String) {
        val arr = JSONArray()
        val cur = list(ctx)
        for (i in 0 until cur.length()) {
            val o = cur.optJSONObject(i) ?: continue
            if (o.optString("id") != id) arr.put(o)
        }
        write(ctx, arr)
    }

    fun clear(ctx: android.content.Context) { write(ctx, JSONArray()) }

    private fun write(ctx: android.content.Context, arr: JSONArray) {
        runCatching { file(ctx).writeText(arr.toString()) }
    }
}