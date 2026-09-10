package com.lynko.app

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * LocalSend-style receive server (Apache-2.0, localsend.org).
 * Endpoints mirror LocalSend v2: /info, /register, /api/lynko/v2/prepare-upload,
 * /api/lynko/v2/upload, /api/lynko/v2/cancel.
 *
 * Session flow: sender posts a manifest (id/name/size/sha256) to prepare-upload,
 * phone shows a consent dialog, then the sender POSTs each file's bytes to
 * upload?sessionId&fileId. sha256 verified after write.
 */
class TransferServer(
    private val appContext: Context,
    private val port: Int,
    private val deviceName: String,
    private val onConsentRequest: (TransferSession, (Boolean) -> Unit) -> Unit,
    private val onProgress: (fileId: String, written: Long, total: Long) -> Unit,
    private val onFileDone: (fileId: String, ok: Boolean, path: String?, shaOk: Boolean?) -> Unit,
    private val onSessionDone: (accepted: Int, rejected: Int) -> Unit,
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        const val PROTOCOL_VERSION = "2.0"
        const val DOWNLOAD = "0"
        const val FINISHED = "1"
        const val DECLINED = "2"
    }

    /** One pending/active transfer session (mirrors LocalSend's ReceiveSession). */
    data class TransferSession(
        val id: String,
        val senderAlias: String,
        val senderDeviceModel: String,
        val files: List<LocalSendFile>,
    )

    data class LocalSendFile(
        val id: String,
        val name: String,
        val size: Long,
        val sha256: String?,
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val consentAnswer = AtomicReference<((Boolean) -> Unit)?>(null)

    private class SessionState {
        val files = ConcurrentHashMap<String, LocalSendFile>()
        @Volatile var declined = false
        @Volatile var closed = false
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return try {
            when {
                uri == "/info" -> serveInfo()
                uri == "/register" -> serveRegister(session)
                uri.endsWith("/prepare-upload") -> servePrepareUpload(session)
                uri.endsWith("/upload") -> serveUpload(session)
                uri.endsWith("/cancel") -> serveCancel(session)
                else -> newFixedResponse(Response.Status.NOT_FOUND, "{\"error\":\"not_found\"}")
            }
        } catch (e: Exception) {
            Log.e("lynko", "transfer server error $uri", e)
            newFixedResponse(Response.Status.INTERNAL_ERROR, "{\"error\":\"internal\"}")
        }
    }

    /** GET /info — discovery payload, LocalSend-compatible shape. */
    private fun serveInfo(): Response {
        val json = JSONObject()
            .put("alias", deviceName)
            .put("version", PROTOCOL_VERSION)
            .put("deviceModel", Build.MODEL ?: "android")
            .put("deviceType", "headless")
            .put("download", JSONObject())
            .put("fingerprint", android.provider.Settings.Secure.getString(
                appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
        return newFixedResponse(Response.Status.OK, json.toString())
    }

    /** POST /register — sender announces itself (we only ack). */
    private fun serveRegister(session: IHTTPSession): Response {
        session.parseBody(mutableMapOf())
        return newFixedResponse(Response.Status.OK, "{\"ok\":true}")
    }

    /** POST /api/lynko/v2/prepare-upload — manifest + user consent gate. */
    private fun servePrepareUpload(session: IHTTPSession): Response {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val manifest = JSONObject(body["postData"] ?: "{}")
        val sender = manifest.optJSONObject("sender") ?: JSONObject()
        val sid = manifest.optString("sessionId", java.util.UUID.randomUUID().toString())

        val filesJson = manifest.optJSONArray("files") ?: JSONArray()
        val st = SessionState()
        val list = ArrayList<LocalSendFile>()
        for (i in 0 until filesJson.length()) {
            val f = filesJson.getJSONObject(i)
            val file = LocalSendFile(
                id = f.optString("id"),
                name = f.optString("fileName", "file.bin"),
                size = f.optLong("size", 0L),
                sha256 = f.optString("sha256").takeIf { it.isNotBlank() },
            )
            st.files[file.id] = file
            list.add(file)
        }
        if (list.isEmpty()) return newFixedResponse(Response.Status.BAD_REQUEST, "{\"error\":\"empty\"}")
        sessions[sid] = st

        // Consent: block the HTTP thread until the user answers on the phone UI.
        val latch = java.util.concurrent.CountDownLatch(1)
        var accepted = false
        onConsentRequest(
            TransferSession(sid, sender.optString("alias", "PC"), sender.optString("deviceModel", ""), list)
        ) { ok ->
            accepted = ok
            latch.countDown()
        }
        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!accepted || st.declined) {
            sessions.remove(sid)
            return newFixedResponse(Response.Status.FORBIDDEN, newFinishJson(sid).put("status", DECLINED).toString())
        }

        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("id", f.id))
        return newFixedResponse(Response.Status.OK,
            newFinishJson(sid).put("status", FINISHED).put("files", arr).toString())
    }

    /** POST /api/lynko/v2/upload?sessionId=&fileId= — raw file bytes. */
    private fun serveUpload(session: IHTTPSession): Response {
        val parms = session.parameters
        val sid = parms["sessionId"]?.firstOrNull() ?: return badRequest("sessionId")
        val fileId = parms["fileId"]?.firstOrNull() ?: return badRequest("fileId")
        val st = sessions[sid] ?: return badRequest("unknown session")
        val file = st.files[fileId] ?: return badRequest("unknown file")
        if (st.closed) return badRequest("session closed")

        // Stream the request body: read exactly Content-Length bytes.
        // (Relying on read()-to-EOF deadlocks: NanoHTTPD keep-alive sockets
        // never EOF before the next request arrives.)
        val cl = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val tmp = java.io.File(appContext.cacheDir, "lynko-tx-$fileId.tmp")
        var written = 0L
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        session.inputStream.use { input ->
            java.io.FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (cl < 0 || written < cl) {
                    val want = if (cl < 0) buf.size else minOf(buf.size.toLong(), cl - written).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    written += n
                    onProgress(fileId, written, if (file.size > 0) file.size else cl)
                }
            }
        }
        val shaOk = file.sha256?.let {
            java.util.HexFormat.of().formatHex(digest.digest()) == it
        }
        if (shaOk == false) {
            tmp.delete()
            onFileDone(fileId, false, null, false)
            return newFixedResponse(Response.Status.BAD_REQUEST, "{\"error\":\"sha_mismatch\"}")
        }
        val path = FileRx.saveStream(appContext, tmp, file.name)
        st.files.remove(fileId)
        if (st.files.isEmpty()) {
            st.closed = true
            sessions.remove(sid)
            onSessionDone(1, 0)
        }
        onFileDone(fileId, true, path, shaOk)
        return newFixedResponse(Response.Status.OK, newFinishJson(sid).toString())
    }

    /** POST /api/lynko/v2/cancel?sessionId= */
    private fun serveCancel(session: IHTTPSession): Response {
        val sid = session.parameters["sessionId"]?.firstOrNull() ?: return badRequest("sessionId")
        sessions[sid]?.let {
            it.closed = true
            it.declined = true
            sessions.remove(sid)
        }
        return newFixedResponse(Response.Status.OK, newFinishJson(sid).toString())
    }

    private fun newFinishJson(sid: String) = JSONObject().put("sessionId", sid)

    private fun badRequest(what: String): Response =
        newFixedResponse(Response.Status.BAD_REQUEST, "{\"error\":\"$what\"}")

    private fun newFixedResponse(status: Response.IStatus, body: String): Response =
        newChunkedResponse(status, "application/json", ByteArrayInputStream(body.toByteArray()))
}
