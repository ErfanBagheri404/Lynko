package com.lynko.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent offline send queue (NectarLink parity).
 *
 * Files that can't be sent (no desktop linked, or the link dropped mid-batch)
 * are snapshotted byte-for-byte into filesDir/send_queue/<id>.part and listed
 * in filesDir/send_queue.json. On retry each snapshot is revalidated against
 * the size+mtime recorded at queue time; a stale or missing snapshot fails the
 * file with an err_queue_* Loc reason instead of silently sending garbage.
 *
 * Ponytail: one desktop per phone — a retry goes to whatever host is linked
 * now. Add per-host routing when Lynko supports several peers at once.
 */
object SendQueue {
    data class Entry(
        val id: String,
        val file: String,   // snapshot filename inside dir()
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: String,
        val mtime: Long,    // snapshot lastModified at queue time
        val host: String,
        val port: Int,
        val pin: String,
    ) {
        fun fileIn(ctx: Context) = File(dir(ctx), file)

        /** Snapshot bytes back into the send pipeline as one staged candidate. */
        fun toStaged(ctx: Context) = ShareSender.Staged(id, name, size, mime, sha256, fileIn(ctx))
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "send_queue.json")
    private fun dir(ctx: Context) = File(ctx.filesDir, "send_queue")

    @Synchronized
    fun list(ctx: Context): List<Entry> = runCatching {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                id = o.optString("id"), file = o.optString("file"),
                name = o.optString("name"), mime = o.optString("mime"),
                size = o.optLong("size"), sha256 = o.optString("sha256"),
                mtime = o.optLong("mtime"), host = o.optString("host"),
                port = o.optInt("port", 53317), pin = o.optString("pin"),
            ).takeIf { it.id.isNotBlank() && it.file.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    /**
     * Move [st]'s staged bytes into the queue and record them. Returns the new
     * entry, or null when the bytes are gone (nothing worth queueing).
     */
    @Synchronized
    fun persist(
        ctx: Context, st: ShareSender.Staged,
        host: String, port: Int, pin: String?,
    ): Entry? {
        val dst = runCatching {
            val d = dir(ctx); d.mkdirs()
            val t = File(d, st.id + ".part")
            if (st.file.absolutePath != t.absolutePath) {
                if (!st.file.renameTo(t)) { st.file.copyTo(t, overwrite = true); st.file.delete() }
            }
            t.takeIf { it.exists() }
        }.getOrNull() ?: return null
        val e = Entry(
            id = st.id, file = dst.name, name = st.name, mime = st.mime,
            size = dst.length(), sha256 = st.sha256, mtime = dst.lastModified(),
            host = host, port = port, pin = pin.orEmpty(),
        )
        write(ctx, list(ctx).filter { it.id != e.id } + e)
        watch(ctx)
        return e
    }

    /** Drop an entry (sent or failed); deletes its snapshot too. */
    @Synchronized
    fun drop(ctx: Context, id: String) {
        val e = list(ctx).firstOrNull { it.id == id } ?: return
        runCatching { e.fileIn(ctx).delete() }
        write(ctx, list(ctx).filter { it.id != id })
    }

    /** "" when the snapshot still matches queue time, else an err_queue_* key. */
    @Synchronized
    fun validate(ctx: Context, e: Entry): String {
        val f = e.fileIn(ctx)
        if (!f.exists()) return "err_queue_missing"
        if (f.length() != e.size || f.lastModified() != e.mtime) return "err_queue_changed"
        return ""
    }

    private fun write(ctx: Context, entries: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject()
                    .put("id", e.id).put("file", e.file).put("name", e.name)
                    .put("mime", e.mime).put("size", e.size).put("sha256", e.sha256)
                    .put("mtime", e.mtime).put("host", e.host)
                    .put("port", e.port).put("pin", e.pin))
            }
            // Write-then-rename so a crash mid-save can't lose the queue.
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    // ------------------------------------------------------------- link-up retry

    private val watching = AtomicBoolean(false)

    /** Poll for the link coming back; started by the first queued transfer and
     *  self-stops when the queue empties. (ShareSender.retryQueued is the real
     *  entry point — anything else can call it too.) */
    fun watch(ctx: Context) {
        if (!watching.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread({
            try {
                while (list(app).isNotEmpty()) {
                    Thread.sleep(4000)
                    if (!PhoneState.link || ShareSender.busy) continue
                    // One attempt at a time; a failed attempt backs off so a
                    // flapping link can't spin the sender.
                    if (ShareSender.retryQueued(app)) Thread.sleep(15_000)
                }
            } catch (_: InterruptedException) {
            } finally {
                watching.set(false)
            }
        }, "lynko-send-queue").apply { isDaemon = true }.start()
    }
}
