package com.config.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
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

    companion object {
        private const val TAG = "DomainVPN"
        private const val COOLDOWN_MS = 2000L
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

        // 1. Быстрая проверка текста события
        val eventText = event.text?.joinToString(" ") ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        var url = extractUrl(eventText)
        if (url.isEmpty()) url = extractUrl(contentDesc)

        // 2. Если не нашли — быстрое сканирование дерева (только 30 узлов, 2 уровня)
        if (url.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null) {
                url = findUrlInWindowFast(root)
                root.recycle()
            }
        }

        if (url.isEmpty()) return

        android.util.Log.d(TAG, "URL: $url")

        val matchedDomain = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true) ||
            extractHost(url).equals(domain, ignoreCase = true)
        }

        if (matchedDomain != null) {
            lastTriggered = now
            android.util.Log.d(TAG, "MATCHED: $matchedDomain")

            if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                autoConnectVpn(matchedDomain)
            }
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

    // Быстрое сканирование — макс 30 узлов, 2 уровня вглубь
    private fun findUrlInWindowFast(root: AccessibilityNodeInfo): String {
        var count = 0
        val maxNodes = 30
        val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))

        while (queue.isNotEmpty() && count < maxNodes) {
            val (node, depth) = queue.poll()
            count++

            if (depth > 2) continue

            // Проверяем text и contentDescription
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            for (candidate in listOf(text, desc)) {
                if (candidate.isNotEmpty()) {
                    val extracted = extractUrl(candidate)
                    if (extracted.isNotEmpty()) return extracted
                }
            }

            // Добавляем детей
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(Pair(child, depth + 1))
            }
        }
        return ""
    }
}
