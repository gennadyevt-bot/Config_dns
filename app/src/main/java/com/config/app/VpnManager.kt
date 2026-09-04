package com.config.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wireguard.android.backend.Backend as WgBackend
import com.wireguard.android.backend.GoBackend as WgGoBackend
import com.wireguard.android.backend.Tunnel as WgBackendTunnel
import com.wireguard.config.Config as WgConfig
import org.amnezia.awg.backend.Backend as AwgBackend
import org.amnezia.awg.backend.GoBackend as AwgGoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel as AwgBackendTunnel
import org.amnezia.awg.config.Config as AwgConfig
import java.io.ByteArrayInputStream

class VpnManager private constructor(private val context: Context) {

    // Стандартный WireGuard: поддерживает IncludedApplications (App VPN)
    private val wgBackend: WgBackend = WgGoBackend(context.applicationContext)

    // AmneziaWG: junk-параметры Jc/Jmin/Jmax/S1/S2/H1-H4 маскируют трафик
    // от DPI РНК. НЕ поддерживает IncludedApplications — поэтому два бэкенда.
    private val awgBackend: AwgBackend = AwgGoBackend(context.applicationContext, NoopTunnelActionHandler())

    private var currentWgConfig: WgConfig? = null
    private var currentAwgConfig: AwgConfig? = null
    private var usingAwg = false
    private val vpnStateStorage = VpnStateStorage(context)

    var onStatusChanged: ((VpnStatus) -> Unit)? = null
    var onServerChanged: ((ServerInfo?) -> Unit)? = null

    private var currentServer: ServerInfo? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        var globalStatus: VpnStatus = VpnStatus.DISCONNECTED

        @Volatile
        private var instance: VpnManager? = null

        fun getInstance(context: Context): VpnManager {
            return instance ?: synchronized(this) {
                instance ?: VpnManager(context).also { instance = it }
            }
        }

        fun destroyInstance() {
            instance = null
        }
    }

    fun getPrepareIntent(activity: Activity): android.content.Intent? {
        return VpnService.prepare(activity)
    }

    fun isPrepared(): Boolean {
        return VpnService.prepare(context) == null
    }

    fun connect(server: ServerInfo) {
        scope.launch {
            try {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    withContext(Dispatchers.Main) {
                        updateStatus(VpnStatus.ERROR)
                        showToast("Ошибка: разрешение VPN не дано. Откройте приложение и нажмите CONNECT.")
                    }
                    return@launch
                }

                currentServer = server
                withContext(Dispatchers.Main) {
                    onServerChanged?.invoke(server)
                    updateStatus(VpnStatus.CONNECTING)
                }

                vpnStateStorage.setWasConnected(true)
                vpnStateStorage.setLastServer(server.id)

                val serviceIntent = Intent(context, VpnKeepAliveService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                val includedApps = AppVpnStorage(context).getSelectedPackages().toList()

                // Конфиг с junk-параметрами (AmneziaWG) идёт через AWG-бэкенд —
                // он обходит DPI РНК. Обычные конфиги — через WireGuard с App VPN.
                val wantsAwg = server.jc.isNotEmpty() && server.jc != "0"
                if (wantsAwg) {
                    connectAwg(server, includedApps)
                } else {
                    connectWg(server, includedApps)
                }

                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.CONNECTED)
                    StopVpnWidget.updateWidget(context, VpnStatus.CONNECTED)
                }
            } catch (e: Exception) {
                val err = e.message ?: e.toString()
                android.util.Log.e("ConfigVPN", "Connect failed", e)
                vpnStateStorage.setWasConnected(false)
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.ERROR)
                    StopVpnWidget.updateWidget(context, VpnStatus.ERROR)
                    showToast("Ошибка: $err")
                }
            }
        }
    }

    private suspend fun connectWg(server: ServerInfo, includedApps: List<String>) {
        val configString = buildConfigString(server, includedApps, withAwg = false)
        android.util.Log.d("ConfigVPN", "WG config: $configString")

        val config = WgConfig.parse(ByteArrayInputStream(configString.toByteArray()))
        currentWgConfig = config
        usingAwg = false

        val tunnel = WgTunnel.getInstance()
        try {
            wgBackend.setState(tunnel, WgBackendTunnel.State.UP, config)
        } catch (e: Exception) {
            if (includedApps.isNotEmpty()) {
                android.util.Log.w("ConfigVPN", "Backend failed with IncludedApplications, retrying without...")
                val fallbackConfig = WgConfig.parse(ByteArrayInputStream(buildConfigString(server).toByteArray()))
                wgBackend.setState(tunnel, WgBackendTunnel.State.UP, fallbackConfig)
                withContext(Dispatchers.Main) {
                    showToast("App VPN: приложение не найдено, VPN работает для всех")
                }
            } else {
                throw e
            }
        }
    }

    private suspend fun connectAwg(server: ServerInfo, includedApps: List<String>) {
        try {
            val configString = buildConfigString(server, emptyList(), withAwg = true)
            android.util.Log.d("ConfigVPN", "AWG config: $configString")

            val config = AwgConfig.parse(ByteArrayInputStream(configString.toByteArray()))
            currentAwgConfig = config
            usingAwg = true

            awgBackend.setState(AwgTunnel.getInstance(), AwgBackendTunnel.State.UP, config)
            if (includedApps.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    showToast("AmneziaWG: App VPN не поддерживается для этого сервера, VPN работает для всех")
                }
            }
        } catch (e: Exception) {
            // Фолбэк: сервер не принял junk-параметры — пробуем обычный WireGuard
            android.util.Log.w("ConfigVPN", "AWG failed, falling back to plain WireGuard", e)
            connectWg(server, includedApps)
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.DISCONNECTING)
                }

                vpnStateStorage.setWasConnected(false)
                context.stopService(Intent(context, VpnKeepAliveService::class.java))

                if (usingAwg) {
                    awgBackend.setState(AwgTunnel.getInstance(), AwgBackendTunnel.State.DOWN, currentAwgConfig)
                } else {
                    wgBackend.setState(WgTunnel.getInstance(), WgBackendTunnel.State.DOWN, currentWgConfig)
                }
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.DISCONNECTED)
                    currentServer = null
                    onServerChanged?.invoke(null)
                    StopVpnWidget.updateWidget(context, VpnStatus.DISCONNECTED)
                }
            } catch (e: Exception) {
                val err = e.message ?: e.toString()
                android.util.Log.e("ConfigVPN", "Disconnect failed", e)
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.ERROR)
                    StopVpnWidget.updateWidget(context, VpnStatus.ERROR)
                    showToast("Ошибка: $err")
                }
            }
        }
    }

    fun getStatus(): VpnStatus = globalStatus
    fun getCurrentServer(): ServerInfo? = currentServer

    private fun hasIPv6Address(address: String): Boolean {
        return address.split(',').any { it.trim().contains(':') }
    }

    private fun sanitizeAllowedIPs(allowedIPs: String, interfaceAddress: String): String {
        val entries = allowedIPs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val v6 = hasIPv6Address(interfaceAddress)
        val cleaned = entries.filter { entry ->
            if (entry.contains(':')) {
                // Никогда не маршрутизируем весь IPv6 в туннель (::/0) — без
                // IPv6-адреса на интерфейсе это чёрная дыра и блокирует интернет.
                entry != "::/0" && v6
            } else {
                true
            }
        }
        return if (cleaned.isEmpty()) "0.0.0.0/0" else cleaned.joinToString(", ")
    }

    private fun sanitizeDns(dns: String, interfaceAddress: String): String {
        val entries = dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val v6 = hasIPv6Address(interfaceAddress)
        val cleaned = entries.filter { !it.contains(':') || v6 }
        return if (cleaned.isEmpty()) "1.1.1.1" else cleaned.joinToString(", ")
    }

    private fun buildConfigString(server: ServerInfo, includedApps: List<String> = emptyList(), withAwg: Boolean = false): String {
        val allowedIPs = sanitizeAllowedIPs(server.peerAllowedIPs, server.interfaceAddress)
        val dns = sanitizeDns(server.interfaceDns, server.interfaceAddress)
        return buildString {
            appendLine("[Interface]")
            appendLine("Address = ${server.interfaceAddress}")
            appendLine("DNS = $dns")
            appendLine("PrivateKey = ${server.interfacePrivateKey}")

            if (withAwg) {
                // Junk-параметры AmneziaWG — маскируют WireGuard от DPI (обход блокировок РНК)
                if (server.jc.isNotEmpty() && server.jc != "0") appendLine("Jc = ${server.jc}")
                if (server.jmin.isNotEmpty() && server.jmin != "0") appendLine("Jmin = ${server.jmin}")
                if (server.jmax.isNotEmpty() && server.jmax != "0") appendLine("Jmax = ${server.jmax}")
                if (server.s1.isNotEmpty() && server.s1 != "0") appendLine("S1 = ${server.s1}")
                if (server.s2.isNotEmpty() && server.s2 != "0") appendLine("S2 = ${server.s2}")
                if (server.h1.isNotEmpty() && server.h1 != "0") appendLine("H1 = ${server.h1}")
                if (server.h2.isNotEmpty() && server.h2 != "0") appendLine("H2 = ${server.h2}")
                if (server.h3.isNotEmpty() && server.h3 != "0") appendLine("H3 = ${server.h3}")
                if (server.h4.isNotEmpty() && server.h4 != "0") appendLine("H4 = ${server.h4}")
            } else {
                val pm = context.packageManager
                val validApps = includedApps.filter { pkg ->
                    try { pm.getApplicationInfo(pkg, 0); true }
                    catch (e: Exception) { android.util.Log.w("ConfigVPN", "App not installed: $pkg"); false }
                }
                validApps.forEach { appendLine("IncludedApplications = $it") }
            }

            appendLine("[Peer]")
            appendLine("PublicKey = ${server.peerPublicKey}")
            if (server.peerPresharedKey.isNotEmpty()) {
                appendLine("PresharedKey = ${server.peerPresharedKey}")
            }
            appendLine("AllowedIPs = $allowedIPs")
            appendLine("Endpoint = ${server.peerEndpoint}")
            appendLine("PersistentKeepalive = ${server.peerPersistentKeepalive}")
        }
    }

    private fun updateStatus(status: VpnStatus) {
        globalStatus = status
        onStatusChanged?.invoke(status)
    }

    private fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
