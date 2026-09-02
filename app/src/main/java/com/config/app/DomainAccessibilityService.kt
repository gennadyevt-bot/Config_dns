package com.config.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DomainAccessibilityService : AccessibilityService() {

    private var vpnManager: VpnManager? = null
    private var domainStorage: DomainVpnStorage? = null
    private var lastTriggered = 0L
    private var lastUrl = ""
    private var lastMatchedDomain = ""
    private var isVpnAutoConnected = false

    companion object {
        private const val TAG = "DomainVPN"
        private const val COOLDOWN_MS = 1500L
        private val URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        private val DOMAIN_REGEX = Regex("""(?:https?://)?(?:www\.)?([a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}(?:\.[-a-zA-Z0-9]+)*)""")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        vpnManager = VpnManager.getInstance(this)
        domainStorage = DomainVpnStorage(this)
        android.util.Log.d(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        if (!storage.isEnabled()) return
        val domains = storage.getDomains()
        if (domains.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastTriggered < COOLDOWN_MS) return

        val eventText = event.text?.joinToString(" ") ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val packageName = event.packageName?.toString() ?: ""

        var url = extractUrl(eventText)
        if (url.isEmpty()) url = extractUrl(contentDesc)

        if (url.isEmpty()) return

        if (url != lastUrl) {
            isVpnAutoConnected = false
        }
        lastUrl = url

        android.util.Log.d(TAG, "URL: $url from $packageName")

        val matchedDomain = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true) ||
            extractHost(url).equals(domain, ignoreCase = true) ||
            extractHost(url).endsWith(".$domain", ignoreCase = true)
        }

        if (matchedDomain != null) {
            if (matchedDomain != lastMatchedDomain || !isVpnAutoConnected) {
                lastMatchedDomain = matchedDomain
                lastTriggered = now
                android.util.Log.d(TAG, "MATCHED: $matchedDomain")

                if (VpnManager.globalStatus == VpnStatus.DISCONNECTED && !isVpnAutoConnected) {
                    isVpnAutoConnected = true
                    autoConnectVpn(matchedDomain)
                }
            }
        } else {
            lastMatchedDomain = ""
            isVpnAutoConnected = false
        }
    }

    override fun onInterrupt() {}

    private fun autoConnectVpn(domain: String) {
        android.util.Log.d(TAG, "Auto-connecting for: $domain")
        val context = this
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val servers = ServerStorage(context).loadServers()
                val validServer = servers.firstOrNull {
                    it.interfacePrivateKey.isNotEmpty() &&
                    it.peerPublicKey.isNotEmpty() &&
                    it.peerEndpoint.isNotEmpty()
                }
                validServer?.let { server ->
                    vpnManager?.connect(server)
                    android.util.Log.d(TAG, "Connected to ${server.name}")
                    showConnectedNotification(domain)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Auto-connect failed", e)
            }
        }
    }

    private fun showConnectedNotification(domain: String) {
        val channelId = "domain_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Domain VPN", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VPN активирован")
            .setContentText("Для $domain")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(2002, notification)
    }

    private fun extractUrl(text: String): String {
        if (text.isEmpty()) return ""
        val match = URL_REGEX.find(text)
        if (match != null) return match.value
        val domainMatch = DOMAIN_REGEX.find(text)
        if (domainMatch != null) {
            val host = domainMatch.groupValues[1]
            return "https://$host"
        }
        return ""
    }

    private fun extractHost(url: String): String {
        return url.removePrefix("https://").removePrefix("http://").removePrefix("www.").split("/")[0].split(":")[0]
    }
}
