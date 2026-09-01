package com.config.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
    private val checkInterval = 3000L
    private var appVpnStorage: AppVpnStorage? = null
    private var lastSelectedPackages: Set<String> = emptySet()
    private var lastExcludedPackages: Set<String> = emptySet()

    private val runnable = object : Runnable {
        override fun run() {
            checkAppConfigChanges()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appVpnStorage = AppVpnStorage(this)
        lastSelectedPackages = appVpnStorage?.getSelectedPackages() ?: emptySet()
        lastExcludedPackages = appVpnStorage?.getExcludedPackages() ?: emptySet()
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

    private fun checkAppConfigChanges() {
        val storage = appVpnStorage ?: return
        if (!storage.isEnabled()) {
            android.util.Log.d("AppMonitor", "App VPN disabled")
            return
        }

        val selectedPackages = storage.getSelectedPackages()
        val excludedPackages = storage.getExcludedPackages()
        android.util.Log.d("AppMonitor", "Selected: $selectedPackages, Excluded: $excludedPackages")

        if (selectedPackages == lastSelectedPackages && excludedPackages == lastExcludedPackages) {
            return
        }

        lastSelectedPackages = selectedPackages
        lastExcludedPackages = excludedPackages

        android.util.Log.d("AppMonitor", "App config changed, reconnecting...")

        if (VpnManager.globalStatus == VpnStatus.CONNECTED) {
            reconnectVpn()
        }
    }

    private fun reconnectVpn() {
        val vpnManager = VpnManager.getInstance(this)
        val currentServer = vpnManager.getCurrentServer() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d("AppMonitor", "Disconnecting for reconnect...")
                vpnManager.disconnect()
                kotlinx.coroutines.delay(500)
                android.util.Log.d("AppMonitor", "Connecting with new app config...")
                vpnManager.connect(currentServer)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AppMonitorService, "Конфиг split tunneling обновлён", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("AppMonitor", "Reconnect failed: ${e.message}", e)
            }
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
            .setContentText("Smart App VPN активен — split tunneling")
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