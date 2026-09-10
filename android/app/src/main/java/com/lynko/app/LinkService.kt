package com.lynko.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-side link: NanoHTTPD pairing server (:7912) + Java-WebSocket link
 * server (:7913). Protocol mirrors lynko-core: JSON {t, d} commands/events,
 * "LV1"+JPEG binary frames for screen, "LF1" chunk header for files.
 */
class LinkService : Service() {

    companion object {
        const val TAG = "lynko"
        const val PAIR_PORT = 7912
        const val LINK_PORT = 7913
        const val CHANNEL_ID = "lynko_link"

        @Volatile
        var running: Boolean = false
            private set
    }

    private var pairingServer: PairingServer? = null
    private var linkServer: LinkServer? = null
    private var transferServer: TransferServer? = null
    private var projection: android.media.projection.MediaProjection? = null
    private val screenSession = AtomicReference<ScreenSession?>(null)
    private val audioSession = AtomicReference<AudioSession?>(null)

    /** ALL binary link traffic funnels through this lock — screen frames and
     * audio chunks arrive from different threads and concurrent ws.send()
     * interleaves the writes, corrupting the frame stream (client drops). */
    private val sendLock = Any()

    /** Send backpressure: if the WS layer is holding more than this many
     * unsent bytes, drop the frame instead of queueing it. Java-WebSocket
     * queues unbounded by default; over Wi-Fi, full-res frames at 15 fps
     * outpace the link and the socket dies (buffer overflow). A dropped
     * screen frame is invisible — the next one carries the full picture. */
    private val lastSendAt = java.util.concurrent.atomic.AtomicLong(0)
    private val sendBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var droppedFrames = 0

    private fun broadcastBinary(frame: ByteArray) {
        val server = linkServer ?: return
        val conns = server.connections.toList()
        if (conns.isEmpty()) return
        // Gate: if the previous frame is still being sent (TCP buffer full
        // or Java-WebSocket write-queue not drained yet), drop this frame
        // entirely — the next one carries the latest screen state, and
        // letting the queue grow causes ANR → service crash under MIUI.
        // Gate is released BEFORE the send lock so we never hold both.
        if (!sendBusy.compareAndSet(false, true)) {
            droppedFrames++
            screenSession.get()?.noteDrop()
            if (droppedFrames % 30 == 1) Log.w(TAG, "link saturated — dropped $droppedFrames frames total")
            return
        }
        try {
            synchronized(sendLock) {
                for (ws in conns) {
                    if (ws.isOpen) ws.send(frame)
                }
            }
            lastSendAt.set(System.currentTimeMillis())
        } finally {
            sendBusy.set(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (running) { Log.w(TAG, "link service onCreate while already running — skipping re-init"); return }
        startForeground()
        // Pin to the physical WLAN *before* creating any socket, then watch
        // for network changes: a VPN toggle mid-mirror rebinding to tun0 is
        // exactly what killed the link. onRebind recreates the listener
        // sockets ON THE NEW NETWORK (pre-bind sockets keep their old route
        // — binding alone can't move an already-listening ServerSocket).
        VpnGuard.attach(this) { restartServers() }
        running = true
        startServers()
        advertise()
        Log.i(TAG, "link service up: pair=$PAIR_PORT link=$LINK_PORT")
    }

    /** Create all three listener servers. Idempotent — called again on network
     *  rebinds (VPN toggle, WLAN rejoin) after the old listeners are torn down. */
    private fun startServers() {
        pairingServer = PairingServer(PAIR_PORT)
        pairingServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        // LocalSend-style HTTP receive server — session-consented file pushes
        // (prepare-upload / upload / cancel). Runs beside the WS link.
        transferServer = TransferServer(
            appContext = this,
            port = 7914,
            deviceName = android.os.Build.MODEL ?: "Android",
            onConsentRequest = { session, answer ->
                // Launch a real Activity — a dialog from a Service context
                // would throw (no window token). Result releases the latch.
                val latch = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val i = android.content.Intent(this, TransferConsentActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(TransferConsentActivity.EXTRA_ALIAS, session.senderAlias)
                        .putExtra(TransferConsentActivity.EXTRA_COUNT, session.files.size)
                        .putExtra(TransferConsentActivity.EXTRA_SIZE, run {
                            val kb = session.files.sumOf { it.size } / 1024
                            if (kb > 1024) "${kb / 1024} MB" else "$kb KB"
                        })
                        .putExtra("sessionId", session.id)
                    startActivity(i)
                    TransferConsentHub.pending[session.id] = { ok ->
                        answer(ok)
                        latch.countDown()
                    }
                }
                latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
                TransferConsentHub.pending.remove(session.id)
            },
            onProgress = { fileId, written, total ->
                Log.d(TAG, "tx $fileId: $written/$total")
            },
            onFileDone = { fileId, ok, path, shaOk ->
                Log.i(TAG, "tx done $fileId ok=$ok sha=$shaOk path=$path")
            },
            onSessionDone = { accepted, rejected ->
                Log.i(TAG, "tx session done accepted=$accepted rejected=$rejected")
            },
        ).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        linkServer = LinkServer(LINK_PORT).also {
            // Screen+audio bursts can exceed the default 60s idle window on
            // slow links; widen it so the server never drops a live desktop.
            it.connectionLostTimeout = 300
            it.start()
        }
    }

    /** VpnGuard rebind: network topology changed (VPN up/down, WLAN rejoin).
     *  Sockets bound before the switch keep routing through the old network,
     *  so listeners are recreated on the now-current network. The desktop
     *  auto-reconnects; screen/audio sessions stay alive. */
    private fun restartServers() {
        Log.i(TAG, "network changed — restarting listener servers")
        try { pairingServer?.stop() } catch (_: Exception) {}
        try { transferServer?.stop() } catch (_: Exception) {}
        try { linkServer?.stop() } catch (_: Exception) {}
        startServers()
        advertise()
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lynko link", NotificationManager.IMPORTANCE_LOW).apply {
                    // VISIBILITY_PUBLIC keeps the notification visible on the lock screen.
                    // MIUI's LockScreenClean kills processes whose FGS notification is
                    // hidden (VISIBILITY_SECRET/-1000) — adj 900 = cached background.
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                }
            )
        }
        val notif: Notification =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Lynko")
                    .setContentText("Waiting for your desktop…")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Lynko")
                    .setContentText("Waiting for your desktop…")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build()
            }
        startForeground(1, notif)
    }

    private fun advertise() {
        // NsdManager mDNS advertiser for _lynko._tcp
        val nsd = getSystemService(android.net.nsd.NsdManager::class.java)
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = android.os.Build.MODEL.ifBlank { "Android" }
            serviceType = "_lynko._tcp."
            port = LINK_PORT
            setAttribute("v", "1")
            setAttribute("caps", "screen,audio,input,text,clip,files,notif,batt")
            setAttribute("pin", "1234") // first-run PIN; UI shows the same
        }
        try {
            nsd.registerService(
                serviceInfo,
                android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
                object : android.net.nsd.NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) {
                        Log.i(TAG, "mDNS registered: ${info.serviceName}")
                    }
                    override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, e: Int) {
                        Log.e(TAG, "mDNS register failed: $e")
                    }
                    override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) {
                        Log.i(TAG, "mDNS unregistered")
                    }
                    override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, e: Int) {
                        Log.e(TAG, "mDNS unregister failed: $e")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "mDNS advertise error", e)
        }
    }

    /** Minimal pairing endpoint: POST /pair {"pin":"1234"} -> {"ok":true,...} */
    inner class PairingServer(port: Int) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            Log.i(TAG, "pair http: ${session.method} ${session.uri}")
            if (session.uri != "/pair") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
            val body: String = try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                files["postData"] ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "pair body read failed", e)
                ""
            }
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val pin = json.optString("pin")
            if (pin != "1234") {
                return newFixedLengthResponse(
                    Response.Status.OK, "application/json",
                    JSONObject().put("ok", false).put("error", "bad pin").toString()
                )
            }
            Log.i(TAG, "paired by desktop")
            val resp = JSONObject()
                .put("ok", true)
                .put("error", JSONObject.NULL)
                .put("device_name", Build.MODEL ?: "Android")
                .put("capabilities", JSONObject()
                    .put("screen_capture", true)
                    .put("input_injection", true)
                    .put("text_input", true)
                    .put("clipboard_sync", true)
                    .put("file_transfer", true)
                    .put("notifications", true)
                    .put("audio_capture", false) // real audio lands later
                    .put("battery_status", true))
                .put("link_port", LINK_PORT)
                .put("transfer_port", 7914)
            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
        }
    }

    /** WS link server: JSON commands in, JSON events + LV1 frames out. */
    inner class LinkServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.i(TAG, "desktop connected: ${handshake.resourceDescriptor}")
            LinkNotifier.broadcaster.set { json -> conn.send(json) }
        }
        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "desktop disconnected")
            LinkNotifier.broadcaster.set(null)
            screenSession.getAndSet(null)?.stop()
        }
        override fun onMessage(conn: WebSocket, message: String) {
            try {
                val json = JSONObject(message)
                val t = json.getString("t")
                val d = json.opt("d")
                handleCommand(t, d, conn)
            } catch (e: Exception) {
                Log.e(TAG, "bad command: ${e.message}")
            }
        }
        override fun onMessage(conn: WebSocket, message: java.nio.ByteBuffer) {
            // file chunks from desktop: LF1 magic
            if (message.remaining() > 3) {
                val magic = ByteArray(3).also { message.get(it) }.decodeToString()
                if (magic == "LF1") {
                    val idLen = message.get().toInt()
                    val idBytes = ByteArray(idLen).also { message.get(it) }
                    val id = String(idBytes)
                    val data = ByteArray(message.remaining()).also { message.get(it) }
                    FileRx.writeChunk(applicationContext, id, data)
                }
            }
        }
        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e(TAG, "link error", ex)
        }
        override fun onStart() {
            Log.i(TAG, "link server started on $port")
        }
    }

    private fun handleCommand(t: String, d: Any?, conn: WebSocket) {
        when (t) {
            "status_get" -> sendEvent(conn, "battery", JSONObject()
                .put("pct", BatteryLevel.read(applicationContext))
                .put("charging", BatteryLevel.isCharging(applicationContext)))
            "start_screen" -> startScreenCapture(conn)
            "stop_screen" -> {
                screenSession.getAndSet(null)?.stop()
                sendEvent(conn, "log", JSONObject().put("msg", "screen stopped"))
            }
            "tap" -> {
                val x = (d as? JSONObject)?.optDouble("x") ?: 0.5
                val y = (d as? JSONObject)?.optDouble("y") ?: 0.5
                if (InputInjector.tap(applicationContext, x.toFloat(), y.toFloat())) {
                    sendEvent(conn, "log", JSONObject().put("msg", "tap received"))
                } else {
                    sendEvent(conn, "input_error", JSONObject()
                        .put("kind", "accessibility")
                        .put("hint", "enable Lynko in Settings > Accessibility"))
                }
            }
            "swipe" -> {
                val o = d as? JSONObject
                if (o != null) {
                    if (InputInjector.swipe(
                        applicationContext,
                        o.optDouble("x1", 0.0).toFloat(), o.optDouble("y1", 0.0).toFloat(),
                        o.optDouble("x2", 1.0).toFloat(), o.optDouble("y2", 1.0).toFloat()
                    )) {
                        sendEvent(conn, "log", JSONObject().put("msg", "swipe received"))
                    } else {
                        sendEvent(conn, "input_error", JSONObject()
                            .put("kind", "accessibility")
                            .put("hint", "enable Lynko in Settings > Accessibility"))
                    }
                }
            }
            "drag_start" -> {
                val o = d as? JSONObject
                if (o != null && !InputInjector.dragStart(applicationContext,
                        o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())) {
                    sendEvent(conn, "input_error", JSONObject()
                        .put("kind", "accessibility")
                        .put("hint", "enable Lynko in Settings > Accessibility"))
                }
            }
            "drag_move" -> {
                val o = d as? JSONObject
                if (o != null) InputInjector.dragMove(applicationContext,
                    o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
            }
            "drag_end" -> {
                val o = d as? JSONObject
                if (o != null) InputInjector.dragEnd(applicationContext,
                    o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
            }
            "key" -> {
                val name = (d as? JSONObject)?.optString("key", "") ?: ""
                // Nav keys: accessibility global action. Editing keys: the
                // focused field (no IME switch needed). ENTER/DEL handled by
                // the field actions below; others fall through to nav.
                val ok = when (name) {
                    "ENTER", "Enter", "Return" -> InputInjector.enter()
                    "DEL", "BACKSPACE", "Backspace" -> InputInjector.backspace()
                    else -> InputInjector.navKey(name)
                }
                if (ok) {
                    sendEvent(conn, "log", JSONObject().put("msg", "key: $name"))
                } else {
                    val editing = name.equals("ENTER", true) || name.equals("Enter", true) ||
                        name.equals("Return", true) || name.equals("DEL", true) ||
                        name.equals("BACKSPACE", true) || name.equals("Backspace", true)
                    sendEvent(conn, "input_error", JSONObject()
                        .put("kind", if (editing) "field" else "accessibility")
                        .put("hint", if (editing)
                            "tap a text field in the mirror first, then type" else "enable Lynko in Settings > Accessibility"))
                }
            }
            "text" -> {
                val text = (d as? JSONObject)?.optString("text") ?: ""
                if (InputInjector.typeText(applicationContext, text)) {
                    sendEvent(conn, "log", JSONObject().put("msg", "text (${text.length} chars)"))
                } else {
                    sendEvent(conn, "input_error", JSONObject()
                        .put("kind", "field")
                        .put("hint", "tap a text field in the mirror first, then type"))
                }
            }
            "copy" -> sendEvent(conn, "clipboard", JSONObject().put("text", ClipboardBridge.read(applicationContext)))
            "file_begin" -> {
                val o = d as? JSONObject
                FileRx.begin(
                    o?.optString("id") ?: "",
                    o?.optString("name") ?: "file.bin",
                )
            }
            "file_end" -> {
                val id = (d as? JSONObject)?.optString("id") ?: ""
                val path = FileRx.finalize(applicationContext, id)
                sendEvent(conn, "file_done", JSONObject()
                    .put("id", id)
                    .put("ok", path != null)
                    .put("path", path ?: JSONObject.NULL)
                    .put("error", if (path != null) JSONObject.NULL else "unknown id"))
            }
            "paste" -> {
                val text = (d as? JSONObject)?.optString("text") ?: ""
                ClipboardBridge.write(applicationContext, text)
                sendEvent(conn, "log", JSONObject().put("msg", "clipboard written"))
            }
            "start_audio" -> startAudioCapture(conn)
            "stop_audio" -> {
                audioSession.getAndSet(null)?.stop()
                sendEvent(conn, "log", JSONObject().put("msg", "audio stopped"))
            }
            else -> sendEvent(conn, "log", JSONObject().put("msg", "unknown command: $t"))
        }
    }

    private fun sendEvent(conn: WebSocket, type: String, payload: JSONObject) {
        conn.send(JSONObject().put("t", type).put("d", payload).toString())
    }

    /** Create the MediaProjection ONCE per consent grant; re-creating from the
     * same consent token throws ("don't re-use resultData"). Audio and screen
     * both read from this single instance. */
    private fun obtainProjection(create: Boolean): android.media.projection.MediaProjection? {
        if (projection != null) return projection
        if (!create) return null
        val resultData = ScreenPermission.resultData
        if (resultData == null) {
            // Consent was never granted or was revoked — request again.
            // This posts to the main thread since the WS handler isn't there.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ScreenPermission.requestFromService(this@LinkService)
            }
            return null
        }
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        projection = try {
            mpm.getMediaProjection(ScreenPermission.resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "media projection failed — will re-request", e)
            ScreenPermission.invalidate() // clear stale token
            null
        }
        if (projection == null) {
            // Token expired — re-request consent.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ScreenPermission.requestFromService(this@LinkService)
            }
            return null
        }
        projection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped remotely")
                projection = null
                screenSession.getAndSet(null)?.stop()
                audioSession.getAndSet(null)?.stop()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        return projection
    }

    private fun startScreenCapture(conn: WebSocket) {
        // Requires MediaProjection consent — captured via MainActivity flow.
        val proj = obtainProjection(create = true)
        if (proj == null) {
            sendEvent(conn, "log", JSONObject().put("msg", "screen permission not granted on phone"))
            return
        }
        val old = screenSession.getAndSet(null)
        old?.stop()
        val session = ScreenSession(applicationContext, proj) { jpeg ->
            val frame = ByteArray(3 + jpeg.size)
            "LV1".toByteArray().copyInto(frame)
            jpeg.copyInto(frame, 3)
            broadcastBinary(frame)
        }
        try {
            session.start()
        } catch (e: Exception) {
            Log.e(TAG, "screen start failed — resetting projection + re-consent", e)
            projection?.stop()
            projection = null
            ScreenPermission.invalidate()
            session.stop()
            screenSession.set(null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ScreenPermission.requestFromService(this@LinkService)
            }
            sendEvent(conn, "log", JSONObject().put("msg", "screen consent expired — re-consent on phone"))
            return
        }
        screenSession.set(session)
        sendEvent(conn, "log", JSONObject().put("msg", "screen streaming"))
    }

    private fun startAudioCapture(conn: WebSocket) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            sendEvent(conn, "log", JSONObject().put("msg", "audio capture needs Android 10+"))
            return
        }
        val proj = obtainProjection(create = true)
        if (proj == null) {
            sendEvent(conn, "log", JSONObject().put("msg", "screen permission required for audio capture"))
            return
        }
        val old = audioSession.getAndSet(null)
        old?.stop()
        val session = AudioSession(
            proj,
            sendBinary = { frame -> broadcastBinary(frame) },
        )
        try {
            session.start()
        } catch (e: Exception) {
            Log.e(TAG, "audio start failed — resetting projection + re-consent", e)
            projection?.stop()
            projection = null
            ScreenPermission.invalidate()
            session.stop()
            audioSession.set(null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ScreenPermission.requestFromService(this@LinkService)
            }
            sendEvent(conn, "log", JSONObject().put("msg", "audio needs fresh screen consent on phone"))
            return
        }
        audioSession.set(session)
        sendEvent(conn, "log", JSONObject().put("msg", "audio streaming"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system kills us (memory / MIUI task-swipe),
        // recreate the service so the desktop link + mDNS survive.
        startForeground()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // MIUI kills on task-swipe: schedule a 1s auto-restart (allowed via
        // AlarmManager exact trigger even when background-start is blocked).
        super.onTaskRemoved(rootIntent)
        try {
            val pi = PendingIntent.getService(
                this, 1, Intent(this, LinkService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(AlarmManager::class.java)
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pi)
        } catch (e: Exception) {
            Log.w(TAG, "auto-restart not possible: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.w(TAG, "link service destroyed — pair/link/transfer ports closed")
        running = false
        VpnGuard.detach()
        pairingServer?.stop()
        transferServer?.stop()
        linkServer?.stop()
        screenSession.getAndSet(null)?.stop()
        audioSession.getAndSet(null)?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
