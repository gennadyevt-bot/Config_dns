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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        if (!storage.isEnabled()) return
        val domains = storage.getDomains()
        if (domains.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastTriggered < 3000) return

        val root = rootInActiveWindow ?: return
        val url = findUrlInWindow(root)
        root.recycle()

        if (url.isEmpty() || url == lastUrl) return
        lastUrl = url

        android.util.Log.d("DomainVPN", "Detected URL: $url")

        val matchedDomain = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true)
        }

        if (matchedDomain != null) {
            lastTriggered = now
            android.util.Log.d("DomainVPN", "Matched domain: $matchedDomain")
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
                        } catch (e: Exception) {
                            android.util.Log.e("DomainVPN", "Auto-connect failed", e)
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun findUrlInWindow(root: AccessibilityNodeInfo): String {
        // Сначала ищем по viewIdResourceName — это адресная строка в браузерах
        val urlIds = listOf(
            "url_bar", "mozac_browser_toolbar_url_view", "addressbar_edit",
            "url_bar_title", "location_bar", "omnibox", "edit_url",
            "com.android.chrome:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.opera.browser:id/url_bar",
            "com.yandex.browser:id/omnibox_text"
        )

        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll()
            val viewId = node.viewIdResourceName
            if (viewId != null) {
                for (id in urlIds) {
                    if (viewId.contains(id, ignoreCase = true)) {
                        val text = node.text?.toString() ?: ""
                        val desc = node.contentDescription?.toString() ?: ""
                        val result = text.ifEmpty { desc }
                        if (result.isNotEmpty()) return result
                    }
                }
            }

            // Также проверяем text/contentDescription на наличие http/https
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            for (candidate in listOf(text, desc)) {
                if (candidate.startsWith("http://") || candidate.startsWith("https://") || candidate.contains("www.")) {
                    return candidate
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