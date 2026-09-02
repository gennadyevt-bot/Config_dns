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
    private var lastLoggedEvent = 0L

    companion object {
        private const val TAG = "DomainVPN"
        private const val COOLDOWN_MS = 3000L
        private const val TOAST_COOLDOWN_MS = 1500L
        private const val LOG_COOLDOWN_MS = 500L
        private const val MAX_NODES = 800
        private const val MAX_DEPTH = 10
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
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.brave.browser"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        vpnManager = VpnManager.getInstance(this)
        domainStorage = DomainVpnStorage(this)
        showToast("Domain VPN: сервис запущен")
        android.util.Log.d(TAG, "=== Accessibility Service CONNECTED ===")
        android.util.Log.d(TAG, "Enabled: ${domainStorage?.isEnabled()}")
        android.util.Log.d(TAG, "Domains: ${domainStorage?.getDomains()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        val pkg = event.packageName?.toString() ?: ""
        val eventType = event.eventType
        val eventTypeName = eventTypeToName(eventType)

        // Логируем ВСЕ события от мессенджеров/браузеров (с кулдауном)
        val now = System.currentTimeMillis()
        val isTargetPkg = TELEGRAM_PACKAGES.any { pkg.contains(it) }
        if (isTargetPkg && now - lastLoggedEvent > LOG_COOLDOWN_MS) {
            lastLoggedEvent = now
            android.util.Log.d(TAG, "EVENT [$eventTypeName] pkg=$pkg")
        }

        if (!storage.isEnabled()) {
            if (isTargetPkg && now - lastLoggedEvent > LOG_COOLDOWN_MS) {
                android.util.Log.d(TAG, "Domain VPN DISABLED")
            }
            return
        }

        val domains = storage.getDomains()
        if (domains.isEmpty()) {
            if (isTargetPkg && now - lastLoggedEvent > LOG_COOLDOWN_MS) {
                android.util.Log.d(TAG, "No domains configured")
            }
            return
        }

        if (now - lastTriggered < COOLDOWN_MS) {
            android.util.Log.d(TAG, "Cooldown active")
            return
        }

        android.util.Log.d(TAG, "=== Processing event [$eventTypeName] pkg=$pkg ===")
        android.util.Log.d(TAG, "Domains: $domains")

        var url = ""
        var sourceName = "none"

        // 1. Проверяем event.text
        val eventText = event.text?.joinToString(" ") ?: ""
        if (eventText.isNotEmpty()) {
            android.util.Log.d(TAG, "event.text: '$eventText'")
            url = extractUrl(eventText)
            if (url.isNotEmpty()) {
                sourceName = "event.text"
                android.util.Log.d(TAG, "URL from event.text: $url")
            }
        }

        // 2. Проверяем contentDescription
        if (url.isEmpty()) {
            val contentDesc = event.contentDescription?.toString() ?: ""
            if (contentDesc.isNotEmpty()) {
                android.util.Log.d(TAG, "contentDescription: '$contentDesc'")
                url = extractUrl(contentDesc)
                if (url.isNotEmpty()) {
                    sourceName = "contentDescription"
                    android.util.Log.d(TAG, "URL from contentDescription: $url")
                }
            }
        }

        // 3. Проверяем event.source (кликнутый узел)
        if (url.isEmpty()) {
            val source = event.source
            if (source != null) {
                val srcText = source.text?.toString() ?: ""
                val srcDesc = source.contentDescription?.toString() ?: ""
                val srcClass = source.className?.toString() ?: ""
                android.util.Log.d(TAG, "source.class=$srcClass text='$srcText' desc='$srcDesc'")
                url = extractUrlFromNode(source)
                if (url.isNotEmpty()) {
                    sourceName = "source.node"
                    android.util.Log.d(TAG, "URL from source.node: $url")
                }
                source.recycle()
            } else {
                android.util.Log.d(TAG, "event.source is NULL")
            }
        }

        // 4. Глубокое сканирование окна для мессенджеров/браузеров
        if (url.isEmpty() && isTargetPkg) {
            android.util.Log.d(TAG, "Starting deep scan...")
            val root = rootInActiveWindow
            if (root != null) {
                url = findUrlDeep(root)
                root.recycle()
                if (url.isNotEmpty()) {
                    sourceName = "deep.scan"
                    android.util.Log.d(TAG, "URL from deep scan: $url")
                } else {
                    android.util.Log.d(TAG, "Deep scan: no URL found")
                }
            } else {
                android.util.Log.d(TAG, "rootInActiveWindow is NULL")
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
                triggerVpnConnect(matchedByPkg)
                return
            }
        }

        if (url.isEmpty()) {
            android.util.Log.d(TAG, "No URL found in this event")
            return
        }

        android.util.Log.d(TAG, "Final URL [$sourceName]: $url")
        val host = extractHost(url)
        android.util.Log.d(TAG, "Extracted host: $host")

        val matchedDomain = domains.firstOrNull { domain ->
            val match = url.contains(domain, ignoreCase = true) ||
                        host.equals(domain, ignoreCase = true) ||
                        host.endsWith(".$domain", ignoreCase = true)
            if (match) android.util.Log.d(TAG, "Domain '$domain' MATCHES host '$host'")
            match
        }

        if (matchedDomain != null) {
            lastTriggered = now
            android.util.Log.d(TAG, "=== MATCHED DOMAIN: $matchedDomain ===")
            showToast("VPN: $matchedDomain")
            triggerVpnConnect(matchedDomain)
        } else {
            android.util.Log.d(TAG, "No domain match for host: $host")
        }
    }

    override fun onInterrupt() {
        android.util.Log.d(TAG, "onInterrupt")
    }

    private fun eventTypeToName(type: Int): String {
        return when (type) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICK"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "SELECTED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLLED"
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> "ANNOUNCEMENT"
            else -> "OTHER($type)"
        }
    }

    private fun triggerVpnConnect(domain: String) {
        android.util.Log.d(TAG, "triggerVpnConnect for: $domain")
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            android.util.Log.w(TAG, "VPN NOT PREPARED — showing notification")
            showPrepareNotification(domain)
            return
        }
        android.util.Log.d(TAG, "VPN is prepared")

        val status = VpnManager.globalStatus
        android.util.Log.d(TAG, "Current VPN status: $status")
        if (status == VpnStatus.DISCONNECTED || status == VpnStatus.ERROR) {
            autoConnectVpn(domain)
        } else {
            android.util.Log.d(TAG, "VPN already active, skipping")
        }
    }

    private fun autoConnectVpn(domain: String) {
        android.util.Log.d(TAG, "autoConnectVpn for: $domain")
        val context = this
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val servers = ServerStorage(context).loadServers()
                android.util.Log.d(TAG, "Loaded ${servers.size} servers")
                val validServer = servers.firstOrNull {
                    it.interfacePrivateKey.isNotEmpty() &&
                    it.peerPublicKey.isNotEmpty() &&
                    it.peerEndpoint.isNotEmpty()
                }
                if (validServer != null) {
                    android.util.Log.d(TAG, "Connecting to: ${validServer.name}")
                    vpnManager?.connect(validServer)
                    android.util.Log.d(TAG, "connect() called")
                    showConnectedNotification(domain)
                } else {
                    android.util.Log.w(TAG, "No valid servers found")
                    showToast("Нет валидных серверов")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Auto-connect failed", e)
                showToast("Ошибка: ${e.message}")
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
        if (match != null) {
            android.util.Log.d(TAG, "URL_REGEX matched: ${match.value}")
            return match.value
        }
        val domainMatch = DOMAIN_REGEX.find(text)
        if (domainMatch != null) {
            val host = domainMatch.groupValues[1]
            android.util.Log.d(TAG, "DOMAIN_REGEX matched: $host")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() ?: "" else "",
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
                    if (extracted.isNotEmpty()) {
                        android.util.Log.d(TAG, "Deep scan found URL at depth=$depth: $extracted")
                        return extracted
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(Pair(child, depth + 1))
            }
        }
        android.util.Log.d(TAG, "Deep scan checked $count nodes, no URL")
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
