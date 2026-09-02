package com.config.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val toastHandler = Handler(Looper.getMainLooper())
    private var lastToastTime = 0L

    companion object {
        private const val TAG = "DomainVPN"
        private const val COOLDOWN_MS = 2000L
        private const val TOAST_COOLDOWN_MS = 1000L
        private const val MAX_NODES = 500
        private const val MAX_DEPTH = 8
        private const val NOTIFICATION_ID_PREPARE = 3001

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
            "com.discord",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.yandex.browser",
            "com.microsoft.emmx"
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
        if (!storage.isEnabled()) return
        val domains = storage.getDomains()
        if (domains.isEmpty()) return

        val pkg = event.packageName?.toString() ?: ""
        val eventType = event.eventType

        android.util.Log.d(TAG, "Event: type=$eventType pkg=$pkg")

        val now = System.currentTimeMillis()
        if (now - lastTriggered < COOLDOWN_MS) return

        var url = ""

        // 1. Проверяем текст события (клик, фокус, изменение текста)
        val eventText = event.text?.joinToString(" ") ?: ""
        if (eventText.isNotEmpty()) {
            url = extractUrl(eventText)
            if (url.isNotEmpty()) {
                android.util.Log.d(TAG, "URL from event text: $url")
            }
        }

        // 2. Проверяем contentDescription
        if (url.isEmpty()) {
            val contentDesc = event.contentDescription?.toString() ?: ""
            url = extractUrl(contentDesc)
            if (url.isNotEmpty()) {
                android.util.Log.d(TAG, "URL from contentDesc: $url")
            }
        }

        // 3. Проверяем source node (кликнутый элемент)
        if (url.isEmpty()) {
            val source = event.source
            if (source != null) {
                url = extractUrlFromNode(source)
                if (url.isNotEmpty()) {
                    android.util.Log.d(TAG, "URL from source: $url")
                }
                source.recycle()
            }
        }

        // 4. Для мессенджеров и браузеров — глубокое сканирование окна
        if (url.isEmpty() && TELEGRAM_PACKAGES.any { pkg.contains(it) }) {
            val root = rootInActiveWindow
            if (root != null) {
                url = findUrlDeep(root)
                root.recycle()
                if (url.isNotEmpty()) {
                    android.util.Log.d(TAG, "URL from deep scan: $url")
                }
            }
        }

        // 5. Проверяем packageName против доменов (если приложение само по себе — сайт)
        if (url.isEmpty()) {
            val matchedByPkg = domains.firstOrNull { domain ->
                val domainBase = domain.removePrefix("www.").split(".")[0]
                pkg.contains(domainBase, ignoreCase = true)
            }
            if (matchedByPkg != null) {
                lastTriggered = now
                android.util.Log.d(TAG, "MATCHED by package: $matchedByPkg")
                showToast("VPN: $matchedByPkg (по приложению)")
                triggerVpnConnect(matchedByPkg)
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
            triggerVpnConnect(matchedDomain)
        } else {
            android.util.Log.d(TAG, "No match for: ${extractHost(url)}")
        }
    }

    override fun onInterrupt() {}

    private fun triggerVpnConnect(domain: String) {
        // Проверяем, дано ли разрешение VPN
        if (VpnService.prepare(this) != null) {
            android.util.Log.w(TAG, "VPN not prepared, showing notification")
            showPrepareNotification(domain)
            return
        }

        if (VpnManager.globalStatus == VpnStatus.DISCONNECTED ||
            VpnManager.globalStatus == VpnStatus.ERROR) {
            autoConnectVpn(domain)
        } else {
            android.util.Log.d(TAG, "VPN already active")
        }
    }

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
                } ?: run {
                    showToast("Нет валидных серверов")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Auto-connect failed", e)
                showToast("Ошибка подключения: ${e.message}")
            }
        }
    }

    private fun showPrepareNotification(domain: String) {
        val channelId = "domain_vpn_prepare"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Domain VPN", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("auto_connect_domain", domain)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Разрешите VPN для $domain")
            .setContentText("Нажмите, чтобы открыть приложение и дать разрешение")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID_PREPARE, notification)
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
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() ?: "" else ""

            for (src in listOf(text, desc, hint)) {
                if (src.isNotEmpty()) {
                    val extracted = extractUrl(src)
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

    private fun showToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime < TOAST_COOLDOWN_MS) return
        lastToastTime = now
        toastHandler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
