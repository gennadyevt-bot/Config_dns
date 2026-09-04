package com.config.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class VpnKeepAliveService : Service() {

    companion object {
        private const val TAG = "VpnKeepAlive"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "config_vpn_keepalive"
        private const val CHECK_INTERVAL = 5000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    private var vpnManager: VpnManager? = null
    private var serverStorage: ServerStorage? = null
    private var vpnStateStorage: VpnStateStorage? = null

    private var connectStartMs = 0L
    private var lastSeenStatus: VpnStatus = VpnStatus.DISCONNECTED
    private var statusTicker: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        vpnManager = VpnManager.getInstance(this)
        serverStorage = ServerStorage(this)
        vpnStateStorage = VpnStateStorage(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        startForeground(NOTIF_ID, buildNotification())

        // Восстанавливаем VPN если был подключен
        if (vpnStateStorage?.wasConnected() == true && vpnManager?.getStatus() == VpnStatus.DISCONNECTED) {
            val lastServerId = vpnStateStorage?.getLastServer()
            val servers = serverStorage?.loadServers() ?: emptyList()
            val server = servers.find { it.id == lastServerId } ?: servers.firstOrNull { 
                it.interfacePrivateKey.isNotEmpty() && it.peerPublicKey.isNotEmpty() && it.peerEndpoint.isNotEmpty()
            }

            server?.let {
                Log.i(TAG, "Restoring VPN connection to ${it.name}")
                vpnManager?.connect(it)
            }
        }

        // Периодическая проверка — переподключаем если VPN упал
        startKeepAliveCheck()
        // Ненавязчивый индикатор: текст уведомления показывает прогресс подключения
        startStatusTicker()

        return START_STICKY
    }

    private fun startStatusTicker() {
        statusTicker = object : Runnable {
            override fun run() {
                val status = vpnManager?.getStatus() ?: VpnStatus.DISCONNECTED
                if (status == VpnStatus.CONNECTING && lastSeenStatus != VpnStatus.CONNECTING) {
                    connectStartMs = System.currentTimeMillis()
                }
                lastSeenStatus = status
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val text = when (status) {
                    VpnStatus.CONNECTING -> {
                        val secs = (System.currentTimeMillis() - connectStartMs) / 1000
                        "Подключение… ${secs} сек (это нормально, ждите)"
                    }
                    VpnStatus.CONNECTED -> "VPN подключён"
                    else -> "Мониторинг VPN подключения"
                }
                nm.notify(NOTIF_ID, buildNotification(text))
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(statusTicker!!)
    }

    private fun startKeepAliveCheck() {
        checkRunnable = object : Runnable {
            override fun run() {
                if (vpnStateStorage?.wasConnected() == true && vpnManager?.getStatus() == VpnStatus.DISCONNECTED) {
                    val lastServerId = vpnStateStorage?.getLastServer()
                    val servers = serverStorage?.loadServers() ?: emptyList()
                    val server = servers.find { it.id == lastServerId } ?: servers.firstOrNull { 
                        it.interfacePrivateKey.isNotEmpty() && it.peerPublicKey.isNotEmpty() && it.peerEndpoint.isNotEmpty()
                    }
                    server?.let {
                        Log.i(TAG, "VPN disconnected unexpectedly — reconnecting to ${it.name}")
                        vpnManager?.connect(it)
                    }
                }
                handler.postDelayed(this, CHECK_INTERVAL)
            }
        }
        handler.post(checkRunnable!!)
    }

    private fun buildNotification(text: String = "Мониторинг VPN подключения"): Notification {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Config VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Config VPN Keep-Alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновый мониторинг VPN"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        checkRunnable?.let { handler.removeCallbacks(it) }
        statusTicker?.let { handler.removeCallbacks(it) }
        Log.i(TAG, "Service destroyed")
    }
}
