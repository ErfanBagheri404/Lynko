package com.lynko.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

    private lateinit var pairingServer: PairingServer
    private var linkServer: LinkServer? = null
    private val screenSession = AtomicReference<ScreenSession?>(null)

    override fun onCreate() {
        super.onCreate()
        startForeground()
        running = true
        pairingServer = PairingServer(PAIR_PORT)
        pairingServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        linkServer = LinkServer(LINK_PORT).also { it.start() }
        advertise()
        Log.i(TAG, "link service up: pair=$PAIR_PORT link=$LINK_PORT")
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lynko link", NotificationManager.IMPORTANCE_LOW)
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
            setAttribute("caps", "screen,audio,input,clip,files,notif,battery")
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
            if (session.uri != "/pair") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
            val body = session.inputStream.readBytes().decodeToString()
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
                InputInjector.tap(applicationContext, x.toFloat(), y.toFloat())
                sendEvent(conn, "log", JSONObject().put("msg", "tap received"))
            }
            "swipe" -> {
                val o = d as? JSONObject
                if (o != null) {
                    InputInjector.swipe(
                        applicationContext,
                        o.optDouble("x1", 0.0).toFloat(), o.optDouble("y1", 0.0).toFloat(),
                        o.optDouble("x2", 1.0).toFloat(), o.optDouble("y2", 1.0).toFloat()
                    )
                    sendEvent(conn, "log", JSONObject().put("msg", "swipe received"))
                }
            }
            "key" -> sendEvent(conn, "log", JSONObject().put("msg", "key received (unimplemented)"))
            "text" -> sendEvent(conn, "log", JSONObject().put("msg", "text received (unimplemented)"))
            "copy" -> sendEvent(conn, "clipboard", JSONObject().put("text", ClipboardBridge.read(applicationContext)))
            "paste" -> {
                val text = (d as? JSONObject)?.optString("text") ?: ""
                ClipboardBridge.write(applicationContext, text)
                sendEvent(conn, "log", JSONObject().put("msg", "clipboard written"))
            }
            "start_audio", "stop_audio" ->
                sendEvent(conn, "log", JSONObject().put("msg", "audio not implemented on phone yet"))
            else -> sendEvent(conn, "log", JSONObject().put("msg", "unknown command: $t"))
        }
    }

    private fun sendEvent(conn: WebSocket, type: String, payload: JSONObject) {
        conn.send(JSONObject().put("t", type).put("d", payload).toString())
    }

    private fun startScreenCapture(conn: WebSocket) {
        // Requires MediaProjection consent — captured via MainActivity flow.
        // For the first E2E pass we require the user to have granted it in the UI.
        val resultData = ScreenPermission.resultData
        if (resultData == null) {
            sendEvent(conn, "log", JSONObject().put("msg", "screen permission not granted on phone"))
            return
        }
        val old = screenSession.getAndSet(null)
        old?.stop()
        val session = ScreenSession(applicationContext) { jpeg ->
            val msg = java.nio.ByteBuffer.allocateDirect(3 + jpeg.size)
            msg.put("LV1".toByteArray())
            msg.put(jpeg)
            msg.flip()
            linkServer?.broadcast(msg)
        }
        session.start()
        screenSession.set(session)
        sendEvent(conn, "log", JSONObject().put("msg", "screen streaming"))
    }

    override fun onDestroy() {
        running = false
        pairingServer.stop()
        linkServer?.stop()
        screenSession.getAndSet(null)?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
