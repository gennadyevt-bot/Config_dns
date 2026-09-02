package com.config.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        vpnManager = VpnManager.getInstance(this)
        domainStorage = DomainVpnStorage(this)
        android.util.Log.d("DomainVPN", "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        if (!storage.isEnabled()) return
        val domains = storage.getDomains()
        if (domains.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastTriggered < 3000) return

        var url = ""
        val eventText = event.text?.joinToString(" ") ?: ""
        url = extractUrl(eventText)
        if (url.isEmpty()) {
            url = extractUrl(event.contentDescription?.toString() ?: "")
        }
        if (url.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null) {
                url = findUrlInWindow(root)
                root.recycle()
            }
        }

        if (url.isEmpty()) return

        // Если URL изменился — сбрасываем флаг авто-подключения
        if (url != lastUrl) {
            isVpnAutoConnected = false
        }
        lastUrl = url

        android.util.Log.d("DomainVPN", "Detected URL: $url")

        val matchedDomain = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true)
        }

        if (matchedDomain != null) {
            if (matchedDomain != lastMatchedDomain) {
                lastMatchedDomain = matchedDomain
                lastTriggered = now
                android.util.Log.d("DomainVPN", "MATCHED domain: $matchedDomain")

                if (VpnManager.globalStatus == VpnStatus.DISCONNECTED && !isVpnAutoConnected) {
                    isVpnAutoConnected = true
                    // === АВТОМАТИЧЕСКОЕ ПОДКЛЮЧЕНИЕ VPN ===
                    autoConnectVpn(matchedDomain)
                    // ======================================
                } else {
                    android.util.Log.d("DomainVPN", "VPN already connected or already auto-connected")
                }
            }
        } else {
            // Ушли с сайта — сбрасываем
            lastMatchedDomain = ""
            isVpnAutoConnected = false
        }
    }

    override fun onInterrupt() {}

    private fun autoConnectVpn(domain: String) {
        android.util.Log.d("DomainVPN", "Auto-connecting VPN for domain: $domain")

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
                    android.util.Log.d("DomainVPN", "VPN auto-connected to ${server.name}")
                    showConnectedNotification(domain)
                } ?: run {
                    android.util.Log.e("DomainVPN", "No valid server found for auto-connect")
                    showErrorNotification("Нет валидного сервера для подключения")
                }
            } catch (e: Exception) {
                android.util.Log.e("DomainVPN", "Auto-connect failed", e)
                showErrorNotification("Ошибка подключения: ${e.message}")
            }
        }
    }

    private fun showConnectedNotification(domain: String) {
        val channelId = "domain_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Domain VPN",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VPN активирован")
            .setContentText("Автоматически подключен для $domain")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2002, notification)
    }

    private fun showErrorNotification(message: String) {
        val channelId = "domain_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Domain VPN",
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Domain VPN")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2003, notification)
    }

    private fun extractUrl(text: String): String {
        if (text.isEmpty()) return ""
        val httpRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        val match = httpRegex.find(text)
        if (match != null) return match.value
        val domainRegex = Regex("""[a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}[^\s]*""")
        val domainMatch = domainRegex.find(text)
        if (domainMatch != null) return "https://" + domainMatch.value
        return ""
    }

    private fun findUrlInWindow(root: AccessibilityNodeInfo): String {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            val viewId = node.viewIdResourceName ?: ""
            if (viewId.contains("url", ignoreCase = true) ||
                viewId.contains("address", ignoreCase = true) ||
                viewId.contains("location", ignoreCase = true) ||
                viewId.contains("omnibox", ignoreCase = true)) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val result = text.ifEmpty { desc }
                if (result.isNotEmpty() && (result.contains(".") || result.contains("/"))) {
                    return extractUrl(result)
                }
            }
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            for (candidate in listOf(text, desc)) {
                if (candidate.contains("http://") || candidate.contains("https://") ||
                    candidate.contains("www.") || candidate.matches(Regex(""".*\.[a-zA-Z]{2,}.*"""))) {
                    val extracted = extractUrl(candidate)
                    if (extracted.isNotEmpty()) return extracted
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }
        return ""
    }
}
