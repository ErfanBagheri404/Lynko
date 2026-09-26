package com.lynko.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
        /** 4-digit random PIN — regenerated every launch so stale pairings fail. */
        val PIN: String = java.security.SecureRandom().let { sr ->
            buildString { repeat(4) { append(sr.nextInt(10)) } }
        }

        // Set false synchronously on stop: stopService() tears down on the
        // main looper a beat later, and a UI that waits for onDestroy shows
        // the OLD state (the "stopped but button still says Stop" bug).
        @Volatile
        var running: Boolean = false
        @Volatile var connectedClients: Int = 0

        /** Live instance for the UI to talk to (Screen tab local mirror). */
        @Volatile var instance: LinkService? = null

        fun requestLocalMirrorStart() { instance?.startLocalMirror() }
        fun requestLocalMirrorStop() { instance?.stopLocalMirror() }
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

    // Idle watchdog: if all desktop clients disconnect while mirroring, hold
    // the capture pipeline alive for 60 s so a VPN-toggle / Wi-Fi blip
    // reconnect resumes instantly. After 60 s with no client, tear down
    // to release the projection and battery.
    private val captureWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private val captureIdleStop = Runnable {
        val s = screenSession.getAndSet(null) ?: return@Runnable
        s.stop()
        stopLocalMirror()
        Log.i(TAG, "capture idle stop — no desktop for 60 s")
    }
    private fun scheduleCaptureIdleStop() {
        captureWatchdog.removeCallbacksAndMessages(null)
        captureWatchdog.postDelayed(captureIdleStop, 60_000)
    }

    /** Low-latency Wi-Fi lock, held only while mirroring. MIUI parks the
     *  Wi-Fi radio in power-save between bursts, which is exactly the
     *  "sometimes the mirror lags a beat" symptom on a healthy LAN: frames
     *  queue at the radio, then arrive 3-at-a-time. WIFI_MODE_FULL_LOW_LATENCY
     *  (API 29+) keeps the radio attentive; falls back to FULL_PERFORMANCE.
     *  Dropped the moment the mirror stops — never held idle. */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireWifiLowLatency() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            val lock = if (Build.VERSION.SDK_INT >= 29) {
                wm.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "lynko:mirror"
                )
            } else {
                @Suppress("DEPRECATION")
                wm.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "lynko:mirror"
                )
            }
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "wifi low-latency lock unavailable: ${e.message}")
        }
    }

    private fun releaseWifiLowLatency() {
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    /** Screen wake lock, held only while mirroring. The compositor stops
     *  feeding the VirtualDisplay when the screen times out (2 min default
     *  on MIUI) — the mirror then freezes on the last frame and the desktop
     *  shows "waiting for frames" forever. A partial wake lock is NOT
     *  enough: it keeps the CPU alive but not the display compositor.
     *  SCREEN_BRIGHT_WAKE_LOCK is deprecated-but-functional and the only
     *  option a Service has (FLAG_KEEP_SCREEN_ON needs a visible Activity).
     *  Released the moment the mirror stops — never held idle. */
    private var screenLock: android.os.PowerManager.WakeLock? = null

    private fun screenLockHeld(): Boolean = screenLock?.isHeld == true

    /** CPU companion lock. MIUI ignores SCREEN_BRIGHT_WAKE_LOCK (screen still
     *  times out), but a PARTIAL_WAKE_LOCK does keep the CPU + WS + capture
     *  thread alive through sleep — so on unlock (SCREEN_ON → rebuild) frames
     *  resume instantly instead of the link dying. Held with the screen lock,
     *  released with it. */
    private var cpuLock: android.os.PowerManager.WakeLock? = null

    private fun acquireScreenWakeLock() {
        if (screenLockHeld()) return
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE)
                as android.os.PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        or android.os.PowerManager.ON_AFTER_RELEASE,
                "lynko:mirror"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            screenLock = lock
            val cpu = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "lynko:cpu")
            cpu.setReferenceCounted(false)
            cpu.acquire()
            cpuLock = cpu
        } catch (e: Exception) {
            Log.w(TAG, "screen wake lock unavailable: ${e.message}")
        }
    }

    private fun releaseScreenWakeLock() {
        try { screenLock?.release() } catch (_: Exception) {}
        screenLock = null
        try { cpuLock?.release() } catch (_: Exception) {}
        cpuLock = null
    }

    private fun broadcastBinary(frame: ByteArray) {
        val server = linkServer ?: return
        val conns = server.connections.toList()
        if (conns.isEmpty()) return
        val isScreen = frame.size > 3 && frame[0] == 'L'.code.toByte() &&
            frame[1] == 'V'.code.toByte() && frame[2] == '1'.code.toByte()
        // Screen frames: gate on the REAL writer queue depth (Java-WebSocket
        // outQueue via hasBufferedData). If the previous frame is still in the
        // WS layer (TCP buffer full / writer not drained), drop this one —
        // the next frame carries the latest screen state, and letting the
        // queue grow causes ANR → service crash under MIUI. Audio is NEVER
        // gated: 100ms/3KB chunks are tiny and a dropped one is an audible
        // gap, whereas a dropped frame is invisible.
        if (isScreen) {
            val pending = conns.any { it.isOpen && it.hasBufferedData() }
            if (pending || !sendBusy.compareAndSet(false, true)) {
                droppedFrames++
                screenSession.get()?.noteDrop()
                if (droppedFrames % 30 == 1) Log.w(TAG, "link saturated — dropped $droppedFrames frames total")
                return
            }
        } else {
            // Audio is never queue-dropped: a lost 100ms chunk is an audible
            // gap. It waits for the gate (concurrent ws.send interleaves
            // writes and corrupts the frame stream), bounded so a wedged send
            // can't hang the capture thread forever.
            var waited = 0
            while (!sendBusy.compareAndSet(false, true)) {
                if (waited >= 500) return
                Thread.sleep(5)
                waited += 5
            }
        }
        try {
            synchronized(sendLock) {
                for (ws in conns) {
                    // isOpen is only a hint: the socket can close between the
                    // check and send(), which throws on the capture thread and
                    // kills the process. Catch — the next frame retries.
                    if (ws.isOpen) try { ws.send(frame) } catch (_: Exception) {}
                }
            }
            lastSendAt.set(System.currentTimeMillis())
            if (isScreen) screenSession.get()?.noteClean()
        } finally {
            sendBusy.set(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        // Pin to the physical WLAN *before* creating any socket, then watch
        // for network changes. On VPN toggle, all three servers are
        // recreated on wlan0 (restartServers), and the desktop reconnects
        // immediately (delay=ZERO in Rust).
        VpnGuard.attach(this) { restartServers() }
        UsbState.attach(this)
        UsbState.onChange = { reAdvertise() }
        running = true
        instance = this
        startServers()
        advertise()
        Log.i(TAG, "link service up: pair=$PAIR_PORT link=$LINK_PORT")
        // Repaint the phone card the moment the service is REALLY up —
        // startLinkService() returns before onCreate runs, so its renderState
        // still saw running=false ("Not running"). This poke makes the card
        // flip to "Waiting" as soon as the ports are actually listening.
        try { PhoneState.onChange?.invoke() } catch (_: Exception) {}
    }

    /** Create all three listener servers. Idempotent — called again on network
     *  rebinds (VPN toggle, WLAN rejoin) after the old listeners are torn down. */
    private fun startServers() {
        startPairAndTransferServers()
        linkServer = LinkServer(LINK_PORT).also {
            // Screen+audio bursts can exceed the default 60s idle window on
            // slow links; widen it so the server never drops a live desktop.
            it.connectionLostTimeout = 300
            // Nagle is fatal for a mirror: JPEG frames leave the encoder as
            // one 24KB buffer, but between them the link goes quiet, and the
            // next burst's first small writes (JSON events, WS ping/pong,
            // partial frames) get held for an ACK round-trip before TCP lets
            // them out. On Wi-Fi that's 40-200ms of invisible stall per
            // burst — exactly the "lots of delay" symptom. Ship packets the
            // moment they exist.
            it.isTcpNoDelay = true
            // Rebind insurance: after a quick destroy→restart (task-swipe,
            // MIUI kill, START_STICKY recreate) the old socket sits in
            // TIME_WAIT and the new bind dies with "Address already in use" —
            // killing 7913 forever while 7912/7914 survive. SO_REUSEADDR lets
            // the fresh listener take the port immediately.
            it.isReuseAddr = true
            it.start()
        }
    }

    /** Pairing + transfer servers only — called from VPN rebind so the live
     *  link server (and its WebSocket connection) is never killed mid-mirror. */
    private fun startPairAndTransferServers() {
        pairingServer = PairingServer(PAIR_PORT)
        pairingServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        // LocalSend Protocol v2.2 receive server — exact official endpoints
        // under /api/localsend/v2/* (prepare-upload / upload / cancel /
        // prepare-download / download / register / info).
        transferServer = TransferServer(
            appContext = this,
            port = TransferServer.DEFAULT_PORT,
            deviceName = android.os.Build.MODEL ?: "Android",
            pin = PinStore.current(this),
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
    }

    /** VpnGuard rebind: network topology changed (VPN up/down, WLAN rejoin).
     *  ALL server sockets must be recreated — Android reroutes the old listen
     *  sockets to tun0 when VPN starts, so new desktop SYNs hit the tunnel
     *  and fail. The live WS connection dies (unavoidable), but the desktop
     *  reconnects immediately (delay=ZERO) and hits the fresh listener on
     *  wlan0. The capture pipeline (screenSession) stays alive — onClose no
     *  longer stops it — so frames resume on the new connection with zero
     *  consent dialog. */
    private fun restartServers() {
        Log.i(TAG, "vpn rebind: restarting all servers on physical Wi-Fi")
        try { pairingServer?.stop() } catch (_: Exception) {}
        try { transferServer?.stop() } catch (_: Exception) {}
        try { linkServer?.stop() } catch (_: Exception) {}
        startServers()
        advertise()
    }

    /** Self-heal for a dead 7913 listener: recreate + rebind with backoff
     *  (max ~2min total), then give up loudly. Runs off the main thread —
     *  WebSocketServer.stop() can block and must never run there. */
    @Volatile private var linkRebindAttempts = 0
    private fun scheduleLinkRebind() {
        if (linkRebindAttempts >= 8) { Log.e(TAG, "link rebind exhausted — 7913 stays down"); return }
        linkRebindAttempts++
        val delay = 1500L * linkRebindAttempts
        Log.w(TAG, "link listener dead — rebind #$linkRebindAttempts in ${delay}ms")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread {
                try {
                    try { linkServer?.stop() } catch (_: Exception) {}
                    linkServer = LinkServer(LINK_PORT).also {
                        it.connectionLostTimeout = 300
                        it.isReuseAddr = true
                        it.start()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "link rebind failed", e)
                    scheduleLinkRebind()
                }
            }.start()
        }, delay)
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
        // Tap the notification → opens MainActivity
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Lynko")
                    .setContentText(Loc.t("phone", "notif_waiting"))
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentIntent(launch)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Lynko")
                    .setContentText(Loc.t("phone", "notif_waiting"))
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentIntent(launch)
                    .setOngoing(true)
                    .build()
            }
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notif,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (ScreenPermission.isGranted) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0))
        else startForeground(1, notif)
    }

    private fun advertise() {
        // NsdManager mDNS advertiser for _lynko._tcp
        val nsd = getSystemService(android.net.nsd.NsdManager::class.java)
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = android.os.Build.MODEL.ifBlank { "Android" }
            serviceType = "_lynko._tcp."
            port = LINK_PORT
            setAttribute("v", "1")
            setAttribute("cap", "screen,audio,input,text,clip,files,notif,batt")
            // Duplicate under "caps" too: the first desktop builds read "cap",
            // newer builds read either. Both keys cost nothing in a TXT record.
            setAttribute("caps", "screen,audio,input,text,clip,files,notif,batt")
            setAttribute("pin", PIN)
            setAttribute("usb", if (UsbState.connected) "1" else "0")
        }
        // Named listener (stash the handle) so reAdvertise() can unregister
        // cleanly when USB state flips mid-run.
        advertiseListener = object : android.net.nsd.NsdManager.RegistrationListener {
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
        try {
            nsd.registerService(
                serviceInfo,
                android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
                advertiseListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "mDNS advertise error", e)
        }
    }

    /** USB cable plugged/unplugged mid-run: the `usb` TXT flag is stale, so
     *  unregister and re-advertise with the fresh state. Desktop re-resolves
     *  within seconds via mDNS; no socket restart needed (WiFi link is
     *  unaffected by the cable). */
    private var advertiseListener: android.net.nsd.NsdManager.RegistrationListener? = null
    private fun reAdvertise() {
        val nsd = try {
            getSystemService(android.net.nsd.NsdManager::class.java)
        } catch (_: Exception) { null } ?: return
        try {
            advertiseListener?.let { nsd.unregisterService(it) }
        } catch (_: Exception) {}
        advertiseListener = null
        advertise()
    }

    /** Minimal pairing endpoint: POST /pair {"pin":"1234"} -> {"ok":true,...} */
    inner class PairingServer(port: Int) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            Log.i(TAG, "pair http: ${session.method} ${session.uri}")
            // Diagnostic ring — read the input/state chain over plain HTTP
            // when USB/adb is unavailable. Debug builds only.
            if (session.uri == "/debug") {
                val dbg = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) DevLog.dump() else "debug build only"
                return newFixedLengthResponse(Response.Status.OK, "text/plain", dbg)
            }
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
            if (pin != PIN) {
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
                .put("usb", UsbState.connected)
            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
        }
    }

    /** WS link server: JSON commands in, JSON events + LV1 frames out. */
    inner class LinkServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.i(TAG, "desktop connected: ${handshake.resourceDescriptor}")
            connectedClients++
            PhoneState.setLink(true)
            PhoneState.broadcaster = { json ->
                try { conn.send(json.toString()) } catch (e: Exception) { Log.w(TAG, "state push failed: ${e.message}") }
            }
            LinkNotifier.broadcaster.set { json -> if (conn.isOpen) try { conn.send(json) } catch (_: Exception) {} }
            // Clipboard sync phone→desktop (2s poll fallback covers MIUI).
            // The poll thread can wake AFTER the socket closed — send() on a
            // dead connection throws WebsocketNotConnectedException on the
            // polling thread, killing the whole process (accessibility svc
            // included → MIUI "not working" → gestures refused). Guard it.
            ClipSync.start(applicationContext) { json -> if (conn.isOpen) try { conn.send(json) } catch (_: Exception) {} }
            // Reconnect after a blip: cancel the capture idle watchdog —
            // the surviving screenSession is already pushing frames to this
            // new connection — and re-pin the radio for the fresh session.
            captureWatchdog.removeCallbacksAndMessages(null)
            if (screenSession.get() != null) { acquireWifiLowLatency(); acquireScreenWakeLock() }
        }
        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "desktop disconnected")
            connectedClients = (connectedClients - 1).coerceAtLeast(0)
            if (connectedClients == 0) { PhoneState.setLink(false); PhoneState.broadcaster = null }
            LinkNotifier.broadcaster.set(null)
            PhoneAudio.unmute(applicationContext)
            releaseWifiLowLatency()
            releaseScreenWakeLock()
            ClipSync.stop()
            // VPN/TCP blip guard: DO NOT stop the capture pipeline here. The
            // VirtualDisplay + MediaProjection survive the disconnect; a
            // reconnecting desktop resumes frames instantly with no consent
            // dialog. Stop only happens via explicit stop_screen, Stop, or
            // process teardown. A 60s idle watchdog (see scheduleCaptureIdleStop)
            // releases the pipeline if nobody reconnects.
            scheduleCaptureIdleStop()
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
            // Server-level fatal (conn == null): the selector thread is dead
            // and 7913 will refuse every desktop dial forever. Rebind with
            // backoff instead of sitting dead — this was the permanent 10061.
            if (conn == null) scheduleLinkRebind()
        }
        override fun onStart() {
            Log.i(TAG, "link server started on $port")
            linkRebindAttempts = 0
        }
    }

    private fun handleCommand(t: String, d: Any?, conn: WebSocket) {
        when (t) {
            // Desktop identity for the share-sheet peer picker. The phone
            // otherwise only has the socket's IP address to show.
            "hello" -> {
                (d as? JSONObject)?.optString("alias")?.takeIf { it.isNotBlank() }?.let {
                    PeerInfo.alias = it
                    MainActivity.refreshSharePeers()
                }
            }
            // share_offer / share_chunk / share_end are now HTTP-based (LocalSend v2.2).
            "status_get" -> sendEvent(conn, "battery", JSONObject()
                .put("pct", BatteryLevel.read(applicationContext))
                .put("charging", BatteryLevel.isCharging(applicationContext)))
            "start_screen" -> startScreenCapture(conn)
            "stop_screen" -> {
                stopLocalMirror()
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
                        o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(),
                        o.optLong("dt", 0L))) {
                    sendEvent(conn, "input_error", JSONObject()
                        .put("kind", "accessibility")
                        .put("hint", "enable Lynko in Settings > Accessibility"))
                }
            }
            "drag_move" -> {
                val o = d as? JSONObject
                if (o != null) InputInjector.dragMove(applicationContext,
                    o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(),
                    o.optLong("dt", 0L))
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
                ClipSync.markLocal(text)
                ClipboardBridge.write(applicationContext, text)
                sendEvent(conn, "log", JSONObject().put("msg", "clipboard written"))
            }
            "notif_reply" -> {
                val o = d as? JSONObject
                val pkg = o?.optString("app") ?: ""
                // Desktop (serde snake_case) sends "notif_id"; older builds
                // sent camelCase "notifId". Read either so replies survive
                // both ends at any version mix.
                val notifId = when {
                    o?.has("notif_id") == true -> o.optInt("notif_id", -1)
                    o != null -> o.optInt("notifId", -1)
                    else -> -1
                }
                val text = o?.optString("text") ?: ""
                val err = if (pkg.isBlank() || text.isBlank() || notifId < 0)
                    "invalid reply request"
                else NotifReply.reply(pkg, notifId, text)
                if (err == null) {
                    sendEvent(conn, "log", JSONObject().put("msg", "reply sent to $pkg"))
                } else {
                    sendEvent(conn, "input_error", JSONObject().put("kind", "reply").put("hint", err))
                }
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
        // The socket can close while a command handler runs — send() then
        // throws and takes the process down with it. Drop the event instead.
        if (!conn.isOpen) return
        try {
            conn.send(JSONObject().put("t", type).put("d", payload).toString())
        } catch (_: Exception) {}
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
        val grantedProjection = projection
        projection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped remotely")
                if (projection === grantedProjection) stopLocalMirror()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        return projection
    }

    private fun startScreenCapture(conn: WebSocket) {
        if (screenSession.get() != null) return
        // Requires MediaProjection consent — captured via MainActivity flow.
        val proj = obtainProjection(create = true)
        if (proj == null) {
            sendEvent(conn, "log", JSONObject().put("msg", "screen permission not granted on phone"))
            return
        }
        val old = screenSession.getAndSet(null)
        old?.stop()
        val session = ScreenSession(applicationContext, proj,
            onFatal = { message ->
                android.os.Handler(mainLooper).post {
                    Log.e(TAG, message)
                    stopLocalMirror()
                    ScreenPermission.requestFromService(this@LinkService)
                }
            },
            onLive = { PhoneState.setMirror(true) }
        ) { jpeg ->
            val frame = ByteArray(3 + jpeg.size)
            "LV1".toByteArray().copyInto(frame)
            jpeg.copyInto(frame, 3)
            broadcastBinary(frame)
            // Route to phone-local preview (Screen tab) — null when hidden,
            // zero overhead.
            ScreenPreview.sink?.invoke(jpeg)
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
        acquireWifiLowLatency()
        acquireScreenWakeLock()
        // Leave media volume unchanged; muting it can silence playback capture.
        sendEvent(conn, "log", JSONObject().put("msg", "screen streaming"))
    }

    private fun startAudioCapture(conn: WebSocket) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendEvent(conn, "log", JSONObject().put("msg", "Audio unavailable: on phone open Lynko Settings → Allow playback audio, then press Audio again."))
            return
        }
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
            Log.e(TAG, "audio start failed", e)
            session.stop()
            audioSession.set(null)
            sendEvent(conn, "log", JSONObject().put("msg", "Audio unavailable: ${e.message}"))
            return
        }
        audioSession.set(session)
        sendEvent(conn, "log", JSONObject().put("msg", "audio streaming"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system kills us (memory / MIUI task-swipe),
        // recreate the service so the desktop link + mDNS survive.
        startForeground()
        PhoneState.attach(applicationContext)
        if (intent?.getBooleanExtra("start_mirror", false) == true) startLocalMirror()
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
        PhoneState.detach()
        try { PhoneState.onChange?.invoke() } catch (_: Exception) {}
        PhoneAudio.unmute(applicationContext)
        VpnGuard.detach()
        UsbState.detach()
        pairingServer?.stop()
        transferServer?.stop()
        linkServer?.stop()
        screenSession.getAndSet(null)?.stop()
        audioSession.getAndSet(null)?.stop()
        stopLocalMirror()
        instance = null
        super.onDestroy()
    }

    /**
     * Screen tab → service: start a LOCAL mirror (no desktop WS needed).
     * Wired like the desktop's start_stream: MediaProjection consent must
     * already exist (Home tab grants it). The captured JPEGs go to BOTH the
     * desktop WS (if linked) and the phone preview sink.
     */
    fun startLocalMirror() {
        if (screenSession.get() != null) return
        val proj = obtainProjection(create = true)
        if (proj == null) {
            Log.w(TAG, "local mirror: screen consent not granted")
            return
        }
        val old = screenSession.getAndSet(null)
        old?.stop()
        val session = ScreenSession(applicationContext, proj,
            onFatal = { message ->
                android.os.Handler(mainLooper).post {
                    Log.e(TAG, message)
                    stopLocalMirror()
                    ScreenPermission.requestFromService(this@LinkService)
                }
            },
            onLive = { PhoneState.setMirror(true) }
        ) { jpeg ->
            val frame = ByteArray(3 + jpeg.size)
            "LV1".toByteArray().copyInto(frame)
            jpeg.copyInto(frame, 3)
            broadcastBinary(frame)
            ScreenPreview.sink?.invoke(jpeg)
        }
        try {
            session.start()
        } catch (e: Exception) {
            Log.e(TAG, "local mirror start failed", e)
            projection?.stop()
            projection = null
            ScreenPermission.invalidate()
            session.stop()
            screenSession.set(null)
            return
        }
        screenSession.set(session)
        acquireWifiLowLatency()
        acquireScreenWakeLock()
        // Leave media volume unchanged; muting it can silence playback capture.
        Log.i(TAG, "local mirror streaming")
    }

    /** Screen tab → service: stop the mirror pipeline. */
    fun stopLocalMirror() {
        captureWatchdog.removeCallbacksAndMessages(null)
        screenSession.getAndSet(null)?.stop()
        audioSession.getAndSet(null)?.stop()
        val oldProjection = projection
        projection = null
        ScreenPermission.invalidate()
        try { oldProjection?.stop() } catch (_: Exception) {}
        PhoneState.setMirror(false)
        releaseWifiLowLatency()
        releaseScreenWakeLock()
        PhoneAudio.unmute(applicationContext)
        Log.i(TAG, "local mirror stopped")
    }

    fun sharePeers(): List<WebSocket> = linkServer?.connections?.filter { it.isOpen }.orEmpty()

    override fun onBind(intent: Intent?): IBinder? = null
}
