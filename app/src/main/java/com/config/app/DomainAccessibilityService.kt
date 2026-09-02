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
import android.os.Handler
import android.os.Looper

class DomainAccessibilityService : AccessibilityService() {

    private var vpnManager: VpnManager? = null
    private var domainStorage: DomainVpnStorage? = null
    private var lastTriggered = 0L
    private val toastHandler = Handler(Looper.getMainLooper())
    private var lastToastTime = 0L

    companion object {
        private const val TAG = "DomainVPN"
        private const val COOLDOWN_MS = 1500L
        private const val TOAST_COOLDOWN_MS = 500L
        private const val MAX_NODES = 300
        private const val MAX_DEPTH = 6
        private val URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        private val DOMAIN_REGEX = Regex("""(?:https?://)?(?:www\.)?([a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}(?:\.[-a-zA-Z0-9]+)*)""")
        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.plus",
            "com.whatsapp",
            "com.vkontakte.android",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.twitter.android",
            "com.discord"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        vpnManager = VpnManager.getInstance(this)
        domainStorage = DomainVpnStorage(this)
        showToast("Domain VPN: сервис запущен")
        android.util.Log.d(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        if (!storage.isEnabled()) {
            // showToastDebug("Domain VPN: выключен")
            return
        }
        val domains = storage.getDomains()
        if (domains.isEmpty()) {
            // showToastDebug("Domain VPN: нет доменов")
            return
        }

        val pkg = event.packageName?.toString() ?: ""
        val eventType = event.eventType
        val eventTypeName = when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICK"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
            else -> "OTHER($eventType)"
        }

        android.util.Log.d(TAG, "Event: $eventTypeName pkg=$pkg")

        // Для отладки: показываем все события из мессенджеров
        if (TELEGRAM_PACKAGES.any { pkg.contains(it) }) {
            showToastDebug("$eventTypeName: $pkg")
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggered < COOLDOWN_MS) return

        var url = ""

        // 1. Проверяем event.source — кликнутый узел
        val source = event.source
        if (source != null) {
            url = extractUrlFromNode(source)
            if (url.isNotEmpty()) {
                android.util.Log.d(TAG, "URL from source: $url")
                showToastDebug("URL: $url")
            }
            source.recycle()
        }

        // 2. Проверяем текст события
        if (url.isEmpty()) {
            val eventText = event.text?.joinToString(" ") ?: ""
            url = extractUrl(eventText)
            if (url.isNotEmpty()) {
                android.util.Log.d(TAG, "URL from event text: $url")
                showToastDebug("URL text: $url")
            }
        }

        // 3. Проверяем contentDescription события
        if (url.isEmpty()) {
            val contentDesc = event.contentDescription?.toString() ?: ""
            url = extractUrl(contentDesc)
            if (url.isNotEmpty()) {
                android.util.Log.d(TAG, "URL from contentDesc: $url")
                showToastDebug("URL desc: $url")
            }
        }

        // 4. Если это мессенджер — сканируем всё окно глубоко
        if (url.isEmpty() && TELEGRAM_PACKAGES.any { pkg.contains(it) }) {
            val root = rootInActiveWindow
            if (root != null) {
                url = findUrlDeep(root)
                root.recycle()
                if (url.isNotEmpty()) {
                    android.util.Log.d(TAG, "URL from deep scan: $url")
                    showToastDebug("URL scan: $url")
                }
            }
        }

        // 5. Проверяем packageName против доменов
        if (url.isEmpty()) {
            val matchedByPkg = domains.firstOrNull { domain ->
                val domainBase = domain.removePrefix("www.").split(".")[0]
                pkg.contains(domainBase, ignoreCase = true)
            }
            if (matchedByPkg != null) {
                lastTriggered = now
                android.util.Log.d(TAG, "MATCHED by package: $matchedByPkg")
                showToast("VPN: $matchedByPkg (по приложению)")
                if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                    autoConnectVpn(matchedByPkg)
                }
                return
            }
        }

        if (url.isEmpty()) return

        android.util.Log.d(TAG, "URL found: $url")

        val matchedDomain = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true) ||
            extractHost(url).equals(domain, ignoreCase = true) ||
            extractHost(url).endsWith(".$domain", ignoreCase = true)
        }

        if (matchedDomain != null) {
            lastTriggered = now
            android.util.Log.d(TAG, "MATCHED: $matchedDomain")
            showToast("VPN: $matchedDomain")
            if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                autoConnectVpn(matchedDomain)
            }
        } else {
            showToastDebug("Не совпало: ${extractHost(url)}")
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

    private fun extractUrlFromNode(node: AccessibilityNodeInfo): String {
        val sources = listOf(
            node.text?.toString() ?: "",
            node.contentDescription?.toString() ?: "",
            node.hintText?.toString() ?: "",
            node.viewIdResourceName ?: ""
        )
        for (src in sources) {
            if (src.isNotEmpty()) {
                val extracted = extractUrl(src)
                if (extracted.isNotEmpty()) return extracted
            }
        }
        return ""
    }

    private fun findUrlDeep(root: AccessibilityNodeInfo): String {
        var count = 0
        val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))
        val visited = java.util.HashSet<Int>()

        while (queue.isNotEmpty() && count < MAX_NODES) {
            val (node, depth) = queue.poll()
            count++
            if (depth > MAX_DEPTH) continue

            val nodeId = System.identityHashCode(node)
            if (visited.contains(nodeId)) continue
            visited.add(nodeId)

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val hint = node.hintText?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            for (candidate in listOf(text, desc, hint, viewId)) {
                if (candidate.isNotEmpty()) {
                    val extracted = extractUrl(candidate)
                    if (extracted.isNotEmpty()) return extracted
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(Pair(child, depth + 1))
            }
        }
        return ""
    }

    private fun showToast(message: String) {
        toastHandler.post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showToastDebug(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime < TOAST_COOLDOWN_MS) return
        lastToastTime = now
        toastHandler.post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}