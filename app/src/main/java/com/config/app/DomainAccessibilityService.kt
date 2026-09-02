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
import android.view.accessibility.AccessibilityWindowInfo
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
        private const val COOLDOWN_MS = 2500L
        private const val TOAST_COOLDOWN_MS = 2000L
        private const val MAX_NODES = 1000
        private const val MAX_DEPTH = 12
        private const val NOTIFICATION_ID_PREPARE = 3001

        private val URL_REGEX = Regex("""https?://[^\s<>"{}|\\^`\[\]]+""")
        private val DOMAIN_REGEX = Regex("""(?:https?://)?(?:www\.)?([a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}(?:\.[-a-zA-Z0-9]+)*)""")

        // Пакеты, которые мы отслеживаем
        private val TARGET_PACKAGES = setOf(
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
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser"
        )

        // Activity-классы, которые сигнализируют об открытии веб-контента
        private val WEBVIEW_CLASSES = setOf(
            "webview", "web_view", "WebView", "Webview", "article", "Article",
            "instant", "Instant", "browser", "Browser", "customtabs", "CustomTabs"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        vpnManager = VpnManager.getInstance(this)
        domainStorage = DomainVpnStorage(this)
        showToast("Domain VPN: сервис запущен")
        android.util.Log.d(TAG, "=== Service connected ===")
        android.util.Log.d(TAG, "Domains: ${domainStorage?.getDomains()}")
        android.util.Log.d(TAG, "Enabled: ${domainStorage?.isEnabled()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        if (!storage.isEnabled()) return

        val domains = storage.getDomains()
        if (domains.isEmpty()) return

        val pkg = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""
        val eventType = event.eventType
        val eventName = eventTypeToName(eventType)

        // Логируем ВСЕ события от target-пакетов
        if (isTargetPackage(pkg)) {
            android.util.Log.d(TAG, "[$eventName] pkg=$pkg class=$className")
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggered < COOLDOWN_MS) return

        // === СТРАТЕГИЯ 1: WINDOW_STATE_CHANGED ===
        // Это главное событие при открытии WebView, браузера, Instant View
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isTargetPackage(pkg)) {
                android.util.Log.d(TAG, "WINDOW_STATE_CHANGED from target pkg=$pkg class=$className")

                // Если это WebView-активность в Telegram — точно открылась ссылка
                if (isWebViewActivity(className)) {
                    android.util.Log.d(TAG, "Detected WebView activity: $className")
                }

                // Сканируем ВСЕ окна на URL
                val url = findUrlInAllWindows()
                if (url.isNotEmpty()) {
                    android.util.Log.d(TAG, "URL from windows scan: $url")
                    processUrl(url, domains, now, "window.scan")
                    return
                }
            }
        }

        // === СТРАТЕГИЯ 2: CLICK / LONG_CLICK ===
        // При клике на ссылку внутри сообщения
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            if (isTargetPackage(pkg)) {
                android.util.Log.d(TAG, "CLICK in target pkg=$pkg")

                // Пробуем извлечь URL из event.text
                val eventText = event.text?.joinToString(" ") ?: ""
                if (eventText.isNotEmpty()) {
                    android.util.Log.d(TAG, "event.text: $eventText")
                    val url = extractUrl(eventText)
                    if (url.isNotEmpty()) {
                        android.util.Log.d(TAG, "URL from event.text: $url")
                        processUrl(url, domains, now, "click.text")
                        return
                    }
                }

                // Пробуем извлечь из contentDescription
                val contentDesc = event.contentDescription?.toString() ?: ""
                if (contentDesc.isNotEmpty()) {
                    android.util.Log.d(TAG, "contentDescription: $contentDesc")
                    val url = extractUrl(contentDesc)
                    if (url.isNotEmpty()) {
                        android.util.Log.d(TAG, "URL from contentDescription: $url")
                        processUrl(url, domains, now, "click.desc")
                        return
                    }
                }

                // Пробуем из event.source
                val source = event.source
                if (source != null) {
                    val url = extractUrlFromNode(source)
                    source.recycle()
                    if (url.isNotEmpty()) {
                        android.util.Log.d(TAG, "URL from source: $url")
                        processUrl(url, domains, now, "click.source")
                        return
                    }
                }

                // Если ничего не нашли в событии — сканируем окна
                val url2 = findUrlInAllWindows()
                if (url2.isNotEmpty()) {
                    android.util.Log.d(TAG, "URL from windows after click: $url2")
                    processUrl(url2, domains, now, "click.windows")
                    return
                }
            }
        }

        // === СТРАТЕГИЯ 3: CONTENT_CHANGED ===
        // Когда меняется содержимое окна (загрузилась страница в WebView)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (isTargetPackage(pkg)) {
                val url = findUrlInAllWindows()
                if (url.isNotEmpty()) {
                    android.util.Log.d(TAG, "URL from content changed: $url")
                    processUrl(url, domains, now, "content.changed")
                    return
                }
            }
        }

        // === СТРАТЕГИЯ 4: packageName совпадает с доменом ===
        // Например, приложение rutracker.org
        val matchedByPkg = domains.firstOrNull { domain ->
            val base = domain.removePrefix("www.").split(".")[0]
            pkg.contains(base, ignoreCase = true)
        }
        if (matchedByPkg != null) {
            lastTriggered = now
            android.util.Log.d(TAG, "MATCH by package: $matchedByPkg")
            showToast("VPN: $matchedByPkg")
            triggerVpnConnect(matchedByPkg)
        }
    }

    override fun onInterrupt() {}

    // ==================== URL ПОИСК ====================

    private fun findUrlInAllWindows(): String {
        val windows = windows
        android.util.Log.d(TAG, "Scanning ${windows.size} windows...")

        for (window in windows) {
            val root = window.root
            if (root != null) {
                val url = findUrlDeep(root)
                root.recycle()
                if (url.isNotEmpty()) {
                    android.util.Log.d(TAG, "URL found in window '${window.title}': $url")
                    return url
                }
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
                if (src.isNotEmpty() && src.length > 4) {
                    val extracted = extractUrl(src)
                    if (extracted.isNotEmpty()) {
                        android.util.Log.d(TAG, "Found URL at depth=$depth: $extracted (source: ${if (src == text) "text" else if (src == desc) "desc" else "hint"})")
                        return extracted
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(Pair(child, depth + 1))
            }
        }
        android.util.Log.d(TAG, "Scanned $count nodes, no URL")
        return ""
    }

    private fun extractUrl(text: String): String {
        if (text.isEmpty()) return ""
        // Ищем полный URL
        val match = URL_REGEX.find(text)
        if (match != null) return match.value
        // Ищем домен без протокола
        val domainMatch = DOMAIN_REGEX.find(text)
        if (domainMatch != null) {
            return "https://${domainMatch.groupValues[1]}"
        }
        return ""
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

    private fun extractHost(url: String): String {
        return url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .split("/")[0].split(":")[0]
    }

    // ==================== ОБРАБОТКА ====================

    private fun processUrl(url: String, domains: Set<String>, now: Long, source: String) {
        val host = extractHost(url)
        android.util.Log.d(TAG, "Processing URL [$source]: $url (host=$host)")

        val matched = domains.firstOrNull { domain ->
            url.contains(domain, ignoreCase = true) ||
            host.equals(domain, ignoreCase = true) ||
            host.endsWith(".$domain", ignoreCase = true)
        }

        if (matched != null) {
            lastTriggered = now
            android.util.Log.d(TAG, "=== MATCHED: $matched ===")
            showToast("VPN: $matched")
            triggerVpnConnect(matched)
        } else {
            android.util.Log.d(TAG, "No match for host: $host against $domains")
        }
    }

    private fun triggerVpnConnect(domain: String) {
        if (VpnService.prepare(this) != null) {
            android.util.Log.w(TAG, "VPN not prepared — showing notification")
            showPrepareNotification(domain)
            return
        }
        val status = VpnManager.globalStatus
        android.util.Log.d(TAG, "VPN status: $status")
        if (status == VpnStatus.DISCONNECTED || status == VpnStatus.ERROR) {
            autoConnectVpn(domain)
        } else {
            android.util.Log.d(TAG, "VPN already active")
        }
    }

    private fun autoConnectVpn(domain: String) {
        android.util.Log.d(TAG, "Auto-connecting for: $domain")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val servers = ServerStorage(this@DomainAccessibilityService).loadServers()
                val valid = servers.firstOrNull {
                    it.interfacePrivateKey.isNotEmpty() &&
                    it.peerPublicKey.isNotEmpty() &&
                    it.peerEndpoint.isNotEmpty()
                }
                if (valid != null) {
                    android.util.Log.d(TAG, "Connecting to ${valid.name}")
                    vpnManager?.connect(valid)
                    showConnectedNotification(domain)
                } else {
                    showToast("Нет валидных серверов")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Auto-connect failed", e)
                showToast("Ошибка: ${e.message}")
            }
        }
    }

    // ==================== УТИЛИТЫ ====================

    private fun isTargetPackage(pkg: String): Boolean {
        return TARGET_PACKAGES.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun isWebViewActivity(className: String): Boolean {
        return WEBVIEW_CLASSES.any { className.contains(it, ignoreCase = true) }
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
            else -> "OTHER($type)"
        }
    }

    // ==================== UI ====================

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
            .setContentText("Нажмите, чтобы открыть приложение")
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

    private fun showToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime < TOAST_COOLDOWN_MS) return
        lastToastTime = now
        toastHandler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
