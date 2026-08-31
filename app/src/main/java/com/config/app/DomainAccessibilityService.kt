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

    override fun onServiceConnected() {
        super.onServiceConnected()
        vpnManager = VpnManager(this)
        domainStorage = DomainVpnStorage(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val storage = domainStorage ?: return
        if (!storage.isEnabled()) return
        if (storage.getDomains().isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastTriggered < 3000) return // debounce 3 sec

        val root = rootInActiveWindow ?: return
        val text = extractText(root)
        root.recycle()

        if (text.isNotEmpty() && storage.containsDomain(text)) {
            lastTriggered = now
            android.util.Log.d("DomainVPN", "Domain matched in: $text")
            if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                CoroutineScope(Dispatchers.IO).launch {
                    val servers = ServerStorage(this@DomainAccessibilityService).loadServers()
                    val valid = servers.firstOrNull {
                        it.interfacePrivateKey.isNotEmpty() &&
                        it.peerPublicKey.isNotEmpty() &&
                        it.peerEndpoint.isNotEmpty()
                    }
                    valid?.let { vpnManager?.connect(it) }
                }
            }
        }
    }

    override fun onInterrupt() {}

    private fun extractText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val text = node.text
        if (!text.isNullOrEmpty()) sb.append(text).append(" ")
        val content = node.contentDescription
        if (!content.isNullOrEmpty()) sb.append(content).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(extractText(child)).append(" ")
            child.recycle()
        }
        return sb.toString()
    }
}
