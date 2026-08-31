package com.config.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.widget.Toast
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.net.InetAddress

class VpnManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private var tunnel: WgTunnel? = null
    private var currentConfig: Config? = null
    private val futureBackend = CompletableDeferred<Backend>()
    private var currentServer: ServerInfo? = null

    var onStatusChanged: ((VpnStatus) -> Unit)? = null
    var onServerChanged: ((ServerInfo?) -> Unit)? = null

    companion object {
        private const val TAG = "ConfigVpnManager"
        private var globalStatus: VpnStatus = VpnStatus.DISCONNECTED
    }

    init {
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Initializing AmneziaWG backend...")
                backend = GoBackend(context, NoopTunnelActionHandler())
                futureBackend.complete(backend!!)
                Log.i(TAG, "AmneziaWG backend initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize backend: ${e.message}", e)
                futureBackend.completeExceptionally(e)
                showToast("Ошибка инициализации AmneziaWG: ${e.message}")
            }
        }
    }

    fun prepareVpn(activity: Activity): Boolean {
        val intent = android.net.VpnService.prepare(activity)
        return intent == null
    }

    fun getPrepareIntent(activity: Activity): android.content.Intent? {
        return android.net.VpnService.prepare(activity)
    }

    fun connect(server: ServerInfo) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== CONNECT START ===")

                if (!futureBackend.isCompleted) {
                    Log.e(TAG, "Backend not initialized yet")
                    showToast("Бэкенд ещё не инициализирован")
                    updateStatus(VpnStatus.DISCONNECTED)
                    return@launch
                }

                if (!validateKeys(server)) {
                    updateStatus(VpnStatus.DISCONNECTED)
                    return@launch
                }

                updateStatus(VpnStatus.CONNECTING)
                currentServer = server
                onServerChanged?.invoke(server)

                Log.i(TAG, "Building config for ${server.name}...")
                val config = buildConfig(server)
                currentConfig = config

                val tunnelName = "config_${server.id}"
                Log.i(TAG, "Creating tunnel: $tunnelName")
                tunnel = WgTunnel(tunnelName) { state ->
                    scope.launch(Dispatchers.Main) {
                        Log.i(TAG, "Tunnel state changed: $state")
                        when (state) {
                            Tunnel.State.UP -> {
                                updateStatus(VpnStatus.CONNECTED)
                                testTunnelConnectivity()
                            }
                            Tunnel.State.DOWN -> updateStatus(VpnStatus.DISCONNECTED)
                            else -> updateStatus(VpnStatus.DISCONNECTED)
                        }
                    }
                }

                Log.i(TAG, "Calling backend.setState(UP)...")
                val b = futureBackend.await()
                b.setState(tunnel!!, Tunnel.State.UP, config)
                Log.i(TAG, "=== CONNECT SUCCESS ===")

            } catch (e: Exception) {
                Log.e(TAG, "=== CONNECT FAILED ===", e)
                showToast("Ошибка подключения: ${e.message}")
                updateStatus(VpnStatus.ERROR)
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== DISCONNECT START ===")
                updateStatus(VpnStatus.DISCONNECTING)
                tunnel?.let { t ->
                    futureBackend.await().setState(t, Tunnel.State.DOWN, currentConfig)
                }
                updateStatus(VpnStatus.DISCONNECTED)
                currentServer = null
                onServerChanged?.invoke(null)
                Log.i(TAG, "=== DISCONNECT SUCCESS ===")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect failed: ${e.message}", e)
                showToast("Ошибка отключения: ${e.message}")
                updateStatus(VpnStatus.ERROR)
            }
        }
    }

    fun switchServer(newServer: ServerInfo) {
        scope.launch(Dispatchers.IO) {
            try {
                updateStatus(VpnStatus.SWITCHING)
                tunnel?.let { t ->
                    futureBackend.await().setState(t, Tunnel.State.DOWN, currentConfig)
                }
                delay(500)
                connect(newServer)
            } catch (e: Exception) {
                Log.e(TAG, "Switch failed: ${e.message}", e)
                showToast("Ошибка смены сервера: ${e.message}")
                updateStatus(VpnStatus.ERROR)
            }
        }
    }

    fun getStatus(): VpnStatus = globalStatus
    fun getCurrentServer(): ServerInfo? = currentServer

    fun isVpnActive(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.type == ConnectivityManager.TYPE_VPN
            }
        } catch (e: Exception) { false }
    }

    private fun testTunnelConnectivity() {
        scope.launch(Dispatchers.IO) {
            try {
                val addresses = InetAddress.getAllByName("1.1.1.1")
                Log.i(TAG, "Tunnel connectivity test: resolved ${addresses.size} addresses")
            } catch (e: Exception) {
                Log.w(TAG, "Tunnel connectivity test failed: ${e.message}")
            }
        }
    }

    private fun validateKeys(server: ServerInfo): Boolean {
        if (server.interfacePrivateKey.length != 44) {
            showToast("Невалидный приватный ключ (должен быть 44 символа base64)")
            return false
        }
        if (server.peerPublicKey.length != 44) {
            showToast("Невалидный публичный ключ сервера (должен быть 44 символа base64)")
            return false
        }
        if (!server.peerEndpoint.contains(":")) {
            showToast("Невалидный endpoint (должен быть IP:port или host:port)")
            return false
        }
        return true
    }

    private fun buildConfig(server: ServerInfo): Config {
        val presharedKeyLine = if (server.peerPresharedKey.isNotEmpty()) {
            "PresharedKey = ${server.peerPresharedKey}\n"
        } else {
            ""
        }

        val awgParams = buildString {
            if (server.jc.isNotEmpty() && server.jc != "0") append("Jc = ${server.jc}\n")
            if (server.jmin.isNotEmpty() && server.jmin != "0") append("Jmin = ${server.jmin}\n")
            if (server.jmax.isNotEmpty() && server.jmax != "0") append("Jmax = ${server.jmax}\n")
            if (server.s1.isNotEmpty() && server.s1 != "0") append("S1 = ${server.s1}\n")
            if (server.s2.isNotEmpty() && server.s2 != "0") append("S2 = ${server.s2}\n")
            if (server.h1.isNotEmpty() && server.h1 != "0") append("H1 = ${server.h1}\n")
            if (server.h2.isNotEmpty() && server.h2 != "0") append("H2 = ${server.h2}\n")
            if (server.h3.isNotEmpty() && server.h3 != "0") append("H3 = ${server.h3}\n")
            if (server.h4.isNotEmpty() && server.h4 != "0") append("H4 = ${server.h4}\n")
        }

        val allowedIPs = if (server.peerAllowedIPs.contains("::/0")) {
            Log.w(TAG, "IPv6 (::/0) detected, using IPv4 only")
            "0.0.0.0/0"
        } else {
            server.peerAllowedIPs
        }

        val awgConfig = """
            [Interface]
            Address = ${server.interfaceAddress}
            DNS = ${server.interfaceDns}
            PrivateKey = ${server.interfacePrivateKey}
            ${awgParams}[Peer]
            PublicKey = ${server.peerPublicKey}
            $presharedKeyLine
            AllowedIPs = $allowedIPs
            Endpoint = ${server.peerEndpoint}
            PersistentKeepalive = ${server.peerPersistentKeepalive}
        """.trimIndent()

        Log.d(TAG, "AWG Config generated (keys hidden)")
        return Config.parse(ByteArrayInputStream(awgConfig.toByteArray()))
    }

    private fun updateStatus(status: VpnStatus) {
        globalStatus = status
        scope.launch(Dispatchers.Main) {
            onStatusChanged?.invoke(status)
        }
    }

    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    data class TrafficStats(val rxBytes: Long, val txBytes: Long)

    fun getTrafficStats(): TrafficStats {
        return TrafficStats(0L, 0L)
    }
}
