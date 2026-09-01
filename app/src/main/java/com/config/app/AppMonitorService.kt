package com.config.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 1500L
    private var vpnManager: VpnManager? = null
    private var appVpnStorage: AppVpnStorage? = null
    private var lastForegroundApp: String? = null

    private val runnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        vpnManager = VpnManager(this)
        appVpnStorage = AppVpnStorage(this)
        android.util.Log.d("AppMonitor", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        handler.post(runnable)
        android.util.Log.d("AppMonitor", "Service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
        android.util.Log.d("AppMonitor", "Service destroyed")
    }

    private fun checkForegroundApp() {
        val storage = appVpnStorage ?: return
        if (!storage.isEnabled()) {
            android.util.Log.d("AppMonitor", "App VPN disabled")
            return
        }

        val selectedPackages = storage.getSelectedPackages()
        val excludedPackages = storage.getExcludedPackages()
        android.util.Log.d("AppMonitor", "Selected: $selectedPackages, Excluded: $excludedPackages")

        if (selectedPackages.isEmpty() && excludedPackages.isEmpty()) {
            android.util.Log.d("AppMonitor", "No apps configured")
            return
        }

        val currentApp = getForegroundApp()
        if (currentApp == lastForegroundApp) return
        lastForegroundApp = currentApp

        android.util.Log.d("AppMonitor", "Foreground app: $currentApp, VPN status: ${VpnManager.globalStatus}")

        val currentStatus = VpnManager.globalStatus

        when {
            currentApp != null && selectedPackages.contains(currentApp) && currentStatus == VpnStatus.DISCONNECTED -> {
                android.util.Log.d("AppMonitor", "Whitelist app: $currentApp, connecting...")
                connectVpn(currentApp)
            }
            currentApp != null && excludedPackages.contains(currentApp) && currentStatus == VpnStatus.CONNECTED -> {
                android.util.Log.d("AppMonitor", "Blacklist app: $currentApp, disconnecting...")
                disconnectVpn()
            }
        }
    }

    private fun connectVpn(appName: String) {
        val servers = ServerStorage(this).loadServers()
        android.util.Log.d("AppMonitor", "Servers: ${servers.size}")
        val validServer = servers.firstOrNull {
            it.interfacePrivateKey.isNotEmpty() && it.peerPublicKey.isNotEmpty() && it.peerEndpoint.isNotEmpty()
        }
        android.util.Log.d("AppMonitor", "Valid server: ${validServer?.name}")
        validServer?.let { server ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    android.util.Log.d("AppMonitor", "Calling vpnManager.connect()")
                    vpnManager?.connect(server)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@AppMonitorService, "VPN включён", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AppMonitor", "Connect failed: ${e.message}", e)
                }
            }
        }
    }

    private fun disconnectVpn() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d("AppMonitor", "Calling vpnManager.disconnect()")
                vpnManager?.disconnect()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AppMonitorService, "VPN отключён", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("AppMonitor", "Disconnect failed: ${e.message}", e)
            }
        }
    }

    private fun getForegroundApp(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 5000, time)
            if (stats.isNullOrEmpty()) {
                android.util.Log.d("AppMonitor", "No usage stats")
                return null
            }
            val app = stats.maxByOrNull { it.lastTimeUsed }?.packageName
            android.util.Log.d("AppMonitor", "UsageStats foreground: $app")
            app
        } catch (e: Exception) {
            android.util.Log.e("AppMonitor", "Failed to get foreground app: ${e.message}")
            null
        }
    }

    private fun createNotification(): Notification {
        val channelId = "app_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "App VPN Monitor", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Config VPN")
            .setContentText("Smart App VPN активен")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        fun start(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AppMonitorService::class.java))
        }
    }
}