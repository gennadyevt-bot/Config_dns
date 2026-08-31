package com.config.app

import android.app.ActivityManager
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        handler.post(runnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
    }

    private fun checkForegroundApp() {
        val storage = appVpnStorage ?: return
        if (!storage.isEnabled()) return

        val selectedPackages = storage.getSelectedPackages()
        if (selectedPackages.isEmpty()) return

        val currentApp = getForegroundApp()
        if (currentApp == lastForegroundApp) return
        lastForegroundApp = currentApp

        android.util.Log.d("AppMonitor", "Foreground app: $currentApp")

        val isTargetApp = currentApp != null && selectedPackages.contains(currentApp)
        val currentStatus = VpnManager.globalStatus

        if (isTargetApp && currentStatus == VpnStatus.DISCONNECTED) {
            android.util.Log.d("AppMonitor", "Target app detected: $currentApp, connecting VPN...")

            // Убиваем target app, чтобы он пересоздал соединения через VPN
            killApp(currentApp)

            val servers = ServerStorage(this).loadServers()
            val validServer = servers.firstOrNull {
                it.interfacePrivateKey.isNotEmpty() &&
                it.peerPublicKey.isNotEmpty() &&
                it.peerEndpoint.isNotEmpty()
            }
            validServer?.let {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        kotlinx.coroutines.delay(2000)
                        vpnManager?.connect(it)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                this@AppMonitorService,
                                "VPN включён для $currentApp. Перезапустите приложение, если оно не работает.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AppMonitor", "Auto-connect failed", e)
                    }
                }
            }
        }
        // Auto-disconnect УБРАН — VPN остаётся включённым
    }

    private fun killApp(packageName: String?) {
        if (packageName == null) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            android.util.Log.d("AppMonitor", "Killed background processes for: $packageName")
        } catch (e: Exception) {
            android.util.Log.e("AppMonitor", "Failed to kill app: $packageName", e)
        }
    }

    private fun getForegroundApp(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                time - 5000,
                time
            )
            if (stats.isNullOrEmpty()) return null
            stats.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            android.util.Log.e("AppMonitor", "Failed to get foreground app", e)
            null
        }
    }

    private fun createNotification(): Notification {
        val channelId = "app_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "App VPN Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Config VPN")
            .setContentText("App VPN активен. VPN не отключается автоматически.")
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