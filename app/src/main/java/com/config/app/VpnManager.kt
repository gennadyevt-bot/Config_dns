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
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

class VpnManager private constructor(private val context: Context) {

    private val backend: Backend = GoBackend(context.applicationContext)
    private var currentConfig: Config? = null
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
                // Проверяем prepare ПЕРЕД подключением
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

                val appVpnStorage = AppVpnStorage(context)
                val includedApps = appVpnStorage.getSelectedPackages().toList()
                val configString = buildConfigString(server, includedApps)
                android.util.Log.d("ConfigVPN", "Config string: $configString")

                val config = Config.parse(ByteArrayInputStream(configString.toByteArray()))
                currentConfig = config

                val tunnel = WgTunnel.getInstance()
                try {
                    backend.setState(tunnel, Tunnel.State.UP, config)
                } catch (e: Exception) {
                    if (includedApps.isNotEmpty()) {
                        android.util.Log.w("ConfigVPN", "Backend failed with IncludedApplications, retrying without...")
                        val fallbackConfig = Config.parse(ByteArrayInputStream(buildConfigString(server).toByteArray()))
                        backend.setState(tunnel, Tunnel.State.UP, fallbackConfig)
                        withContext(Dispatchers.Main) {
                            showToast("App VPN: приложение не найдено, VPN работает для всех")
                        }
                    } else {
                        throw e
                    }
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

    fun disconnect() {
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.DISCONNECTING)
                }

                vpnStateStorage.setWasConnected(false)
                context.stopService(Intent(context, VpnKeepAliveService::class.java))

                val tunnel = WgTunnel.getInstance()
                backend.setState(tunnel, Tunnel.State.DOWN, currentConfig)
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

    private fun buildConfigString(server: ServerInfo, includedApps: List<String> = emptyList()): String {
        val allowedIPs = sanitizeAllowedIPs(server.peerAllowedIPs, server.interfaceAddress)
        val dns = sanitizeDns(server.interfaceDns, server.interfaceAddress)
        return buildString {
            appendLine("[Interface]")
            appendLine("Address = ${server.interfaceAddress}")
            appendLine("DNS = $dns")
            appendLine("PrivateKey = ${server.interfacePrivateKey}")

            // AWG parameters removed — standard WireGuard backend does not support them

            val pm = context.packageManager
            val validApps = includedApps.filter { pkg ->
                try { pm.getApplicationInfo(pkg, 0); true }
                catch (e: Exception) { android.util.Log.w("ConfigVPN", "App not installed: $pkg"); false }
            }
            validApps.forEach { appendLine("IncludedApplications = $it") }

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
