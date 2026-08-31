package com.config.app

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.widget.Toast
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

class VpnManager(private val context: Context) {

    private val backend: Backend = GoBackend(context, NoopTunnelActionHandler())
    private var tunnel: WgTunnel? = null
    private var currentConfig: Config? = null

    var onStatusChanged: ((VpnStatus) -> Unit)? = null
    var onServerChanged: ((ServerInfo?) -> Unit)? = null

    private var currentServer: ServerInfo? = null

    companion object {
        var globalStatus: VpnStatus = VpnStatus.DISCONNECTED
    }

    fun getPrepareIntent(activity: Activity): android.content.Intent? {
        return VpnService.prepare(activity)
    }

    fun connect(server: ServerInfo) {
        try {
            currentServer = server
            onServerChanged?.invoke(server)
            updateStatus(VpnStatus.CONNECTING)

            val configString = buildConfigString(server)
            val config = Config.parse(ByteArrayInputStream(configString.toByteArray()))
            currentConfig = config

            tunnel = WgTunnel("config_${server.id}")
            backend.setState(tunnel!!, Tunnel.State.UP, config)

            updateStatus(VpnStatus.CONNECTED)
            StopVpnWidget.updateWidget(context, VpnStatus.CONNECTED)
        } catch (e: Exception) {
            updateStatus(VpnStatus.ERROR)
            StopVpnWidget.updateWidget(context, VpnStatus.ERROR)
            showToast("Ошибка: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            updateStatus(VpnStatus.DISCONNECTING)
            tunnel?.let { backend.setState(it, Tunnel.State.DOWN, currentConfig) }
            updateStatus(VpnStatus.DISCONNECTED)
            currentServer = null
            onServerChanged?.invoke(null)
            StopVpnWidget.updateWidget(context, VpnStatus.DISCONNECTED)
        } catch (e: Exception) {
            updateStatus(VpnStatus.ERROR)
            StopVpnWidget.updateWidget(context, VpnStatus.ERROR)
        }
    }

    fun getStatus(): VpnStatus = globalStatus
    fun getCurrentServer(): ServerInfo? = currentServer

    private fun buildConfigString(server: ServerInfo): String {
        return buildString {
            appendLine("[Interface]")
            appendLine("Address = ${server.interfaceAddress}")
            appendLine("DNS = ${server.interfaceDns}")
            appendLine("PrivateKey = ${server.interfacePrivateKey}")

            if (server.jc.isNotEmpty() && server.jc != "0") appendLine("Jc = ${server.jc}")
            if (server.jmin.isNotEmpty() && server.jmin != "0") appendLine("Jmin = ${server.jmin}")
            if (server.jmax.isNotEmpty() && server.jmax != "0") appendLine("Jmax = ${server.jmax}")
            if (server.s1.isNotEmpty() && server.s1 != "0") appendLine("S1 = ${server.s1}")
            if (server.s2.isNotEmpty() && server.s2 != "0") appendLine("S2 = ${server.s2}")
            if (server.h1.isNotEmpty() && server.h1 != "0") appendLine("H1 = ${server.h1}")
            if (server.h2.isNotEmpty() && server.h2 != "0") appendLine("H2 = ${server.h2}")
            if (server.h3.isNotEmpty() && server.h3 != "0") appendLine("H3 = ${server.h3}")
            if (server.h4.isNotEmpty() && server.h4 != "0") appendLine("H4 = ${server.h4}")

            appendLine("[Peer]")
            appendLine("PublicKey = ${server.peerPublicKey}")
            if (server.peerPresharedKey.isNotEmpty()) {
                appendLine("PresharedKey = ${server.peerPresharedKey}")
            }
            appendLine("AllowedIPs = ${server.peerAllowedIPs}")
            appendLine("Endpoint = ${server.peerEndpoint}")
            appendLine("PersistentKeepalive = ${server.peerPersistentKeepalive}")
        }
    }

    private fun updateStatus(status: VpnStatus) {
        globalStatus = status
        onStatusChanged?.invoke(status)
    }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}
