package com.lynko.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Keeps the Lynko process pinned to the physical Wi-Fi network, VPN or not.
 *
 * Two bugs this fixes:
 *  1. A VPN network reports the transports of its underlying network, so the
 *     old `allNetworks.firstOrNull { hasTransport(TRANSPORT_WIFI) }` could
 *     return the TUNNEL itself. Lynko's servers were then bound to tun0 and
 *     every LAN SYN got RST ("actively refused", os error 10061).
 *  2. The bind happened once at service start. Toggling the VPN later never
 *     re-evaluated it, and sockets created before the bind keep their old
 *     routing — so LinkService must (re)create its servers after the network
 *     actually changed. onRebind exists for exactly that.
 *
 * requestNetwork(Wi-Fi + NOT_VPN) matches the *physical* wlan0 network (the
 * NET_CAPABILITY_NOT_VPN capability is absent on VPN networks) and keeps the
 * callback firing for every WLAN/VPN change for the life of the process.
 */
object VpnGuard {

    private const val TAG = "lynko"

    private var cm: ConnectivityManager? = null
    private var callback: NetworkCallback? = null
    private var bound: Network? = null
    private var onRebind: ((Network) -> Unit)? = null
    private var attached = false

    fun attach(context: Context, onRebind: (Network) -> Unit) {
        if (attached) { VpnGuard.onRebind = onRebind; return }
        attached = true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        VpnGuard.cm = cm
        VpnGuard.onRebind = onRebind

        // 1. Synchronous best-effort bind BEFORE any Lynko socket is created:
        //    pick Wi-Fi networks that are not tunnels.
        val wifi = cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (wifi != null) {
            bind(wifi, "immediate")
        } else {
            Log.w(TAG, "no physical Wi-Fi network found — link may break while VPN is up")
        }

        // 2. Long-lived watcher: fires on VPN toggles, Wi-Fi reconnects,
        //    capability changes. Re-binds when the *selected* network changes.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        callback = object : NetworkCallback() {
            override fun onAvailable(network: Network) { bind(network, "available") }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                bind(network, "capabilities")
            }
            override fun onLost(network: Network) {
                if (network == bound) {
                    bound = null
                    Log.w(TAG, "Wi-Fi network lost — waiting for onAvailable")
                }
            }
        }
        try {
            cm.requestNetwork(request, callback!!)
            Log.i(TAG, "vpn-guard armed on Wi-Fi (non-VPN)")
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork failed: ${e.message}")
        }
    }

    fun detach() {
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        bound = null
        cm = null
        onRebind = null
        attached = false
    }

    private fun bind(network: Network, why: String) {
        val changed = bound != null && bound != network
        if (bound == network) return
        bound = network
        try {
            cm?.bindProcessToNetwork(network)
            Log.i(TAG, "process bound to physical Wi-Fi ($why)")
            // Sockets created BEFORE the bind keep their old route — the
            // server sockets must be recreated or the VPN still owns them.
            if (changed) onRebind?.invoke(network)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork($why) failed: ${e.message}")
        }
    }
}
