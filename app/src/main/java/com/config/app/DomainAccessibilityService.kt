package com.config.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        vpnManager = VpnManager(this)
        domainStorage = DomainVpnStorage(this)
        android.util.Log.d("DomainVPN", "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        if (!storage.isEnabled()) return
        val domains = storage.getDomains()
        if (domains.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastTriggered < 5000) return

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

        if (url.isEmpty() || url == lastUrl) return
        lastUrl = url

        android.util.Log.d("DomainVPN", "Detected URL: $url")

        val matchedDomain = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true)
        }

        if (matchedDomain != null && matchedDomain != lastMatchedDomain) {
            lastMatchedDomain = matchedDomain
            lastTriggered = now
            android.util.Log.d("DomainVPN", "MATCHED domain: $matchedDomain")

            if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                // Показываем уведомление с кнопкой "Подключить VPN"
                showVpnNotification(matchedDomain, url)
            } else {
                android.util.Log.d("DomainVPN", "VPN already connected")
            }
        }
    }

    override fun onInterrupt() {}

    private fun showVpnNotification(domain: String, url: String) {
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

        // Intent для подключения VPN
        val connectIntent = Intent(this, VpnActionReceiver::class.java).apply {
            action = "com.config.app.CONNECT_VPN"
            putExtra("domain", domain)
        }
        val connectPending = PendingIntent.getBroadcast(
            this, 0, connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Сайт требует VPN")
            .setContentText("$domain обнаружен. Нажмите для подключения VPN.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_mylocation, "Подключить VPN", connectPending)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2001, notification)
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