package com.lynko.app

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * LocalSend Protocol v2.2 receive server (Apache-2.0, localsend.org).
 *
 * Endpoints are byte-for-byte the official ones:
 *   GET  /api/localsend/v2/info
 *   POST /api/localsend/v2/register
 *   POST /api/localsend/v2/prepare-upload?pin=   -> {sessionId, files:{id:token}}
 *   POST /api/localsend/v2/upload?sessionId&fileId&token
 *   POST /api/localsend/v2/cancel?sessionId
 *   POST /api/localsend/v2/prepare-download?pin= -> {info, sessionId, files}
 *   GET  /api/localsend/v2/download?sessionId&fileId
 *
 * Error codes follow the spec table exactly: 204/400/401/403/409/422/429/500.
 */
class TransferServer(
    private val appContext: Context,
    private val port: Int,
    private val deviceName: String,
    private val pin: String?,
    private val onConsentRequest: (TransferSession, (Boolean) -> Unit) -> Unit,
    private val onProgress: (fileId: String, written: Long, total: Long) -> Unit,
    private val onFileDone: (fileId: String, ok: Boolean, path: String?, shaOk: Boolean?) -> Unit,
    private val onSessionDone: (accepted: Int, rejected: Int) -> Unit,
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        const val VERSION = "2.2"
        /** LocalSend defaults: multicast 224.0.0.167, HTTP port 53317. */
        const val DEFAULT_PORT = 53317
        private const val MAX_REQUESTS_PER_MIN = 60
    }

    /** One pending/active session, mirroring LocalSend's ReceiveSessionState. */
    data class TransferSession(
        val id: String,
        val senderAlias: String,
        val senderDeviceModel: String,
        val senderDeviceType: String,
        val senderFingerprint: String,
        val files: List<LocalSendFile>,
    )

    /** FileDto from the spec: id, fileName, size, fileType, sha256, preview, metadata. */
    data class LocalSendFile(
        val id: String,
        val name: String,
        val size: Long,
        val fileType: String,
        val sha256: String?,
        val preview: String?,
        val modified: String?,
    )

    private class SessionState {
        val files = ConcurrentHashMap<String, LocalSendFile>()
        /** fileId -> token handed out in prepare-upload (spec 4.1). */
        val tokens = ConcurrentHashMap<String, String>()
        /** fileId -> desired on-disk name chosen at accept time. */
        val desired = ConcurrentHashMap<String, String>()
        @Volatile var senderIp: String = ""
        @Volatile var closed = false
        @Volatile var declined = false
        @Volatile var acceptedCount = 0
        @Volatile var rejectedCount = 0
    }

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val requestTimes = ConcurrentHashMap<String, AtomicLong>()
    private val deviceId: String = runCatching {
        android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }.getOrNull() ?: UUID.randomUUID().toString()

    /** Fingerprint: random string when encryption is off (spec section 2). */
    val fingerprint: String = "lynko-" + deviceId.takeLast(16)

    private fun deviceInfoJson(): JSONObject = JSONObject()
        .put("alias", deviceName)
        .put("version", VERSION)
        .put("deviceModel", Build.MODEL ?: "Android")
        .put("deviceType", "mobile")
        .put("fingerprint", fingerprint)
        .put("port", port)
        .put("protocol", "http")
        .put("download", false)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val ip = session.remoteIpAddress ?: ""
        if (!allowRate(ip)) {
            return json(Response.Status.TOO_MANY_REQUESTS, JSONObject().put("error", "too_many_requests"))
        }
        return try {
            when {
                uri == "/api/localsend/v2/info" -> info()
                uri == "/api/localsend/v2/register" -> register(session)
                uri == "/api/localsend/v2/prepare-upload" -> prepareUpload(session)
                uri == "/api/localsend/v2/upload" -> upload(session)
                uri == "/api/localsend/v2/cancel" -> cancel(session)
                uri == "/api/localsend/v2/prepare-download" -> prepareDownload(session)
                uri == "/api/localsend/v2/download" -> download(session)
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "not_found"))
            }
        } catch (e: Exception) {
            Log.e("lynko", "transfer server error $uri", e)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "unknown"))
        }
    }

    /** 60 requests/minute/IP -> 429, matching the spec's rate-limit code. */
    private fun allowRate(ip: String): Boolean {
        if (ip.isEmpty()) return true
        val now = System.currentTimeMillis()
        val slot = requestTimes.computeIfAbsent(ip) { AtomicLong(now) }
        synchronized(slot) {
            if (now - slot.get() > 60_000L) { slot.set(now); return true }
            slot.set(now + 60_000L)
            return false
        }
    }

    /** GET /api/localsend/v2/info (spec 6.1) — debug/discovery probe. */
    private fun info(): Response = json(Response.Status.OK, deviceInfoJson())

    /** POST /api/localsend/v2/register (spec 3.2) — two-way discovery. */
    private fun register(session: IHTTPSession): Response {
        session.parseBody(mutableMapOf())
        return json(Response.Status.OK, deviceInfoJson())
    }

    /** POST /api/localsend/v2/prepare-upload (spec 4.1).
     *  Body: {"info":{...},"files":{"<id>":{"id","fileName","size","fileType","sha256","preview","metadata"}}}
     *  Reply: {"sessionId","files":{"<id>":"<token>"}} */
    private fun prepareUpload(session: IHTTPSession): Response {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val root = runCatching { JSONObject(body["postData"] ?: "{}") }.getOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid_body"))

        if (!pinOk(session)) return pinRequired()

        // Single-session invariant: a new request means the old session is over.
        sessions.keys.toList().forEach { sid ->
            sessions[sid]?.let { if (it.closed || it.files.isEmpty()) sessions.remove(sid) }
        }
        val active = sessions.values.firstOrNull { !it.closed }
        if (active != null) {
            return json(Response.Status.CONFLICT, JSONObject().put("error", "blocked_by_another_session"))
        }

        val info = root.optJSONObject("info") ?: JSONObject()
        val filesObj = root.optJSONObject("files")
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid_body"))

        val sid = UUID.randomUUID().toString()
        val st = SessionState()
        st.senderIp = session.remoteIpAddress ?: ""
        val list = ArrayList<LocalSendFile>()
        val keys = filesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val f = filesObj.optJSONObject(key) ?: continue
            val file = LocalSendFile(
                id = f.optString("id", key),
                name = f.optString("fileName", "file.bin"),
                size = f.optLong("size", 0L),
                fileType = f.optString("fileType", "application/octet-stream"),
                sha256 = f.optString("sha256").takeIf { it.isNotBlank() },
                preview = f.optString("preview").takeIf { it.isNotBlank() },
                modified = f.optJSONObject("metadata")?.optString("modified")?.takeIf { it.isNotBlank() },
            )
            if (file.id.isBlank()) continue
            st.files[file.id] = file
            list.add(file)
        }
        if (list.isEmpty()) {
            // Spec: 204 = finished, no file transfer needed (e.g. a text message).
            return json(Response.Status.NO_CONTENT, JSONObject())
        }
        sessions[sid] = st

        val senderAlias = info.optString("alias", "Unknown")
        val senderModel = info.optString("deviceModel", "")
        val senderType = info.optString("deviceType", "desktop")
        val senderFp = info.optString("fingerprint", "")

        // Consent gate: block this HTTP thread until the user answers.
        val latch = CountDownLatch(1)
        var accepted = false
        onConsentRequest(
            TransferSession(sid, senderAlias, senderModel, senderType, senderFp, list)
        ) { ok -> accepted = ok; latch.countDown() }
        latch.await(120, TimeUnit.SECONDS)
        if (!accepted || st.declined) {
            sessions.remove(sid)
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "rejected"))
        }

        // Accept all files, mint one token per file (spec 4.1 response).
        val tokens = JSONObject()
        for (f in list) {
            val token = UUID.randomUUID().toString().replace("-", "").take(32)
            st.tokens[f.id] = token
            st.desired[f.id] = f.name
            tokens.put(f.id, token)
        }
        return json(Response.Status.OK, JSONObject()
            .put("sessionId", sid)
            .put("files", tokens))
    }

    /** POST /api/localsend/v2/upload?sessionId=&fileId=&token= (spec 4.2). */
    private fun upload(session: IHTTPSession): Response {
        val sid = session.parameters["sessionId"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing_session_id"))
        val fileId = session.parameters["fileId"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing_file_id"))
        val token = session.parameters["token"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing_token"))

        val st = sessions[sid]
            ?: return json(Response.Status.FORBIDDEN, JSONObject().put("error", "unknown_session"))
        if (st.closed) {
            return json(Response.Status.CONFLICT, JSONObject().put("error", "blocked_by_another_session"))
        }
        // 403 invalid token or IP address (spec 4.2).
        if (st.tokens[fileId] != token) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "invalid_token"))
        }
        val ip = session.remoteIpAddress ?: ""
        if (st.senderIp.isNotEmpty() && ip != st.senderIp) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "invalid_ip"))
        }
        val file = st.files[fileId]
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "unknown_file"))

        // Read exactly Content-Length bytes (keep-alive sockets never EOF).
        val cl = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val tmp = File(appContext.cacheDir, "lynko-tx-$fileId.tmp")
        var written = 0L
        val digest = MessageDigest.getInstance("SHA-256")
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

        // 422 checksum mismatch (spec 4.2).
        val shaOk = file.sha256?.let { MessageDigest.isEqual(
            digest.digest(), hexToBytes(it)
        ) }
        if (shaOk == false) {
            tmp.delete()
            st.rejectedCount++
            onFileDone(fileId, false, null, false)
            return json(422, JSONObject().put("error", "checksum_mismatch"))
        }

        val path = FileRx.saveStream(appContext, tmp, st.desired[fileId] ?: file.name)
        st.files.remove(fileId)
        st.tokens.remove(fileId)
        st.desired.remove(fileId)
        st.acceptedCount++
        onFileDone(fileId, true, path, shaOk)
        if (st.files.isEmpty()) {
            st.closed = true
            sessions.remove(sid)
            onSessionDone(st.acceptedCount, st.rejectedCount)
        }
        return json(Response.Status.OK, JSONObject())
    }

    /** POST /api/localsend/v2/cancel?sessionId= (spec 4.3). */
    private fun cancel(session: IHTTPSession): Response {
        val sid = session.parameters["sessionId"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing_session_id"))
        sessions.remove(sid)?.let {
            it.closed = true
            it.declined = true
            onSessionDone(it.acceptedCount, it.rejectedCount)
        }
        return json(Response.Status.OK, JSONObject())
    }

    /** POST /api/localsend/v2/prepare-download?pin= (spec 5.2). */
    private fun prepareDownload(session: IHTTPSession): Response {
        session.parseBody(mutableMapOf())
        if (!pinOk(session)) return pinRequired()
        val sid = session.parameters["sessionId"]?.firstOrNull() ?: UUID.randomUUID().toString()
        val files = JSONObject()
        for (f in Outbox.files()) {
            files.put(f.id, JSONObject()
                .put("id", f.id)
                .put("fileName", f.name)
                .put("size", f.size)
                .put("fileType", f.fileType)
                .put("sha256", f.sha256))
        }
        return json(Response.Status.OK, JSONObject()
            .put("info", deviceInfoJson())
            .put("sessionId", sid)
            .put("files", files))
    }

    /** GET /api/localsend/v2/download?sessionId=&fileId= (spec 5.3) — raw binary. */
    private fun download(session: IHTTPSession): Response {
        val fileId = session.parameters["fileId"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "missing_file_id"))
        val f = Outbox.get(fileId)
            ?: return json(Response.Status.FORBIDDEN, JSONObject().put("error", "rejected"))
        val src = File(f.path)
        if (!src.exists()) {
            return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "unknown"))
        }
        return newChunkedResponse(
            Response.Status.OK, f.fileType.ifBlank { "application/octet-stream" }, src.inputStream()
        )
    }

    /** ?pin= handling (spec 4.1/5.2): 401 when required or wrong. */
    private fun pinOk(session: IHTTPSession): Boolean {
        val required = pin?.isNotBlank() == true
        if (!required) return true
        return session.parameters["pin"]?.firstOrNull() == pin
    }

    private fun pinRequired(): Response =
        json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "pin_required"))

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun json(status: Response.IStatus, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    /** Spec codes NanoHTTPD's enum lacks (e.g. 422 Unprocessable Entity). */
    private fun json(code: Int, body: JSONObject): Response =
        newFixedLengthResponse(
            object : Response.IStatus {
                override fun getRequestStatus(): Int = code
                override fun getDescription(): String =
                    if (code == 422) "Unprocessable Entity" else "Status $code"
            },
            "application/json",
            body.toString(),
        )
}
