package com.config.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DomainAccessibilityService : AccessibilityService() {

    private var vpnManager: VpnManager? = null
    private var domainStorage: DomainVpnStorage? = null
    private var lastTriggered = 0L
    private var lastUrl = ""

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
        if (now - lastTriggered < 3000) return

        // Собираем URL из всех возможных источников
        var url = ""

        // 1. Пробуем event.text (для некоторых браузеров URL приходит тут)
        val eventText = event.text?.joinToString(" ") ?: ""
        url = extractUrl(eventText)

        // 2. Пробуем event.contentDescription
        if (url.isEmpty()) {
            url = extractUrl(event.contentDescription?.toString() ?: "")
        }

        // 3. Пробуем rootInActiveWindow
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

        if (matchedDomain != null) {
            lastTriggered = now
            android.util.Log.d("DomainVPN", "MATCHED domain: $matchedDomain")
            if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                CoroutineScope(Dispatchers.IO).launch {
                    val servers = ServerStorage(this@DomainAccessibilityService).loadServers()
                    val valid = servers.firstOrNull {
                        it.interfacePrivateKey.isNotEmpty() &&
                        it.peerPublicKey.isNotEmpty() &&
                        it.peerEndpoint.isNotEmpty()
                    }
                    valid?.let {
                        try {
                            kotlinx.coroutines.delay(500)
                            vpnManager?.connect(it)
                            android.util.Log.d("DomainVPN", "VPN connected for domain: $matchedDomain")
                        } catch (e: Exception) {
                            android.util.Log.e("DomainVPN", "Auto-connect failed", e)
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun extractUrl(text: String): String {
        if (text.isEmpty()) return ""
        // Ищем http:// или https://
        val httpRegex = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        val match = httpRegex.find(text)
        if (match != null) return match.value

        // Ищем домен вида domain.com
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

            // Проверяем viewId на URL-related
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

            // Проверяем text/contentDescription напрямую
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