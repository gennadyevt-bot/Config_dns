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
        private const val COOLDOWN_MS = 1500L
        private const val MAX_NODES = 200
        private const val MAX_DEPTH = 5
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

        var url = ""

        // 1. Проверяем event.source — кликнутый узел (самый важный!)
        val source = event.source
        if (source != null) {
            url = extractUrlFromNode(source)
            source.recycle()
        }

        // 2. Проверяем текст события
        if (url.isEmpty()) {
            val eventText = event.text?.joinToString(" ") ?: ""
            url = extractUrl(eventText)
        }

        // 3. Проверяем contentDescription события
        if (url.isEmpty()) {
            val contentDesc = event.contentDescription?.toString() ?: ""
            url = extractUrl(contentDesc)
        }

        // 4. Проверяем packageName (если кликнули в Telegram/VK — проверяем домены)
        if (url.isEmpty()) {
            val pkg = event.packageName?.toString() ?: ""
            val matchedByPkg = domains.firstOrNull { domain ->
                pkg.contains(domain.removePrefix("www.").split(".")[0], ignoreCase = true)
            }
            if (matchedByPkg != null) {
                lastTriggered = now
                android.util.Log.d(TAG, "MATCHED by package: $matchedByPkg")
                if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                    autoConnectVpn(matchedByPkg)
                }
                return
            }
        }

        // 5. Глубокое сканирование дерева
        if (url.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null) {
                url = findUrlDeep(root)
                root.recycle()
            }
        }

        if (url.isEmpty()) return

        android.util.Log.d(TAG, "URL found: $url")

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

    // Извлекаем URL из конкретного узла
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

    // Глубокое сканирование — макс 200 узлов, 5 уровней
    private fun findUrlDeep(root: AccessibilityNodeInfo): String {
        var count = 0
        val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))
        val visited = java.util.HashSet<Int>()

        while (queue.isNotEmpty() && count < MAX_NODES) {
            val (node, depth) = queue.poll()
            count++

            if (depth > MAX_DEPTH) continue

            // Уникальный ID узла
            val nodeId = System.identityHashCode(node)
            if (visited.contains(nodeId)) continue
            visited.add(nodeId)

            // Проверяем все возможные источники URL
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

            // Добавляем детей
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(Pair(child, depth + 1))
            }
        }
        return ""
    }
}