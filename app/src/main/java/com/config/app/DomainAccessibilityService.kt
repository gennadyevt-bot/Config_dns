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
        if (!storage.isEnabled()) {
            android.util.Log.d(TAG, "Domain VPN disabled")
            return
        }
        val domains = storage.getDomains()
        if (domains.isEmpty()) {
            android.util.Log.d(TAG, "No domains configured")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggered < COOLDOWN_MS) return

        val eventText = event.text?.joinToString(" ") ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // 1. Быстрый путь — из текста события
        var url = extractUrl(eventText)
        if (url.isEmpty()) url = extractUrl(contentDesc)

        // 2. Если пусто — сканируем корневое окно (быстро, без глубокой рекурсии)
        if (url.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null) {
                url = findUrlInWindow(root)
                root.recycle()
            }
        }

        // 3. Для кликов по ссылкам — проверяем source события
        if (url.isEmpty() && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            if (source != null) {
                val srcText = source.text?.toString() ?: ""
                val srcDesc = source.contentDescription?.toString() ?: ""
                url = extractUrl(srcText).ifEmpty { extractUrl(srcDesc) }
                source.recycle()
            }
        }

        if (url.isEmpty()) {
            android.util.Log.v(TAG, "No URL found in event from $packageName")
            return
        }

        if (url != lastUrl) {
            isVpnAutoConnected = false
        }
        lastUrl = url

        android.util.Log.d(TAG, "URL detected: $url from $packageName ($className)")

        val matchedDomain = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true) ||
            extractHost(url).equals(domain, ignoreCase = true) ||
            extractHost(url).endsWith(".$domain", ignoreCase = true)
        }

        if (matchedDomain != null) {
            if (matchedDomain != lastMatchedDomain || !isVpnAutoConnected) {
                lastMatchedDomain = matchedDomain
                lastTriggered = now
                android.util.Log.d(TAG, "MATCHED domain: $matchedDomain")

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
                } ?: android.util.Log.e(TAG, "No valid server")
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

    // БЫСТРОЕ сканирование — только корневой уровень + 1 уровень вглубь
    private fun findUrlInWindow(root: AccessibilityNodeInfo): String {
        // Проверяем сам root
        val rootText = root.text?.toString() ?: ""
        val rootDesc = root.contentDescription?.toString() ?: ""
        val rootId = root.viewIdResourceName ?: ""

        // Специальные ID для URL-баров в браузерах
        if (rootId.contains("url", ignoreCase = true) ||
            rootId.contains("address", ignoreCase = true) ||
            rootId.contains("omnibox", ignoreCase = true) ||
            rootId.contains("location", ignoreCase = true)) {
            val result = extractUrl(rootText).ifEmpty { extractUrl(rootDesc) }
            if (result.isNotEmpty()) return result
        }

        // Проверяем прямых детей (1 уровень)
        for (i in 0 until minOf(root.childCount, 20)) {
            val child = root.getChild(i) ?: continue
            val childText = child.text?.toString() ?: ""
            val childDesc = child.contentDescription?.toString() ?: ""
            val childId = child.viewIdResourceName ?: ""

            if (childId.contains("url", ignoreCase = true) ||
                childId.contains("address", ignoreCase = true) ||
                childId.contains("omnibox", ignoreCase = true)) {
                val result = extractUrl(childText).ifEmpty { extractUrl(childDesc) }
                child.recycle()
                if (result.isNotEmpty()) return result
            }

            // Если текст похож на URL
            val extracted = extractUrl(childText).ifEmpty { extractUrl(childDesc) }
            child.recycle()
            if (extracted.isNotEmpty()) return extracted
        }
        return ""
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
