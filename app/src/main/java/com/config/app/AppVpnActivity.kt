package com.config.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVpnActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var btnSave: Button
    private lateinit var appVpnStorage: AppVpnStorage
    private val apps = mutableListOf<AppInfo>()
    private val selectedPackages = mutableSetOf<String>()

    // Популярные приложения — всегда показываем, даже если system
    private val popularApps = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.whatsapp",
        "com.facebook.katana",
        "com.twitter.android",
        "com.x.android",
        "com.zhiliaoapp.musically",
        "com.snapchat.android",
        "com.discord",
        "com.spotify.music",
        "com.netflix.mediaclient",
        "com.google.android.apps.maps",
        "com.google.android.gm",
        "com.google.android.apps.docs",
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.microsoft.emmx",
        "com.vkontakte.android",
        "com.ok.android"
    )

    // Системный мусор — всегда скрываем
    private val systemTrash = setOf(
        "com.android.stk",
        "com.hihonor.android.clone",
        "com.hihonor.android.fmradio",
        "com.hihonor.photos",
        "com.hihonor.soundrecorder",
        "com.hihonor.systemmanager",
        "com.hihonor.gameassistant",
        "com.hihonor.magazine",
        "com.hihonor.detectrepair",
        "com.hihonor.android.pushagent",
        "com.google.android.gms",
        "com.google.android.modulemetadata",
        "com.google.android.networkstack",
        "com.google.android.tts",
        "com.google.android.apps.wellbeing",
        "com.google.mainline.adservices",
        "com.google.mainline.telemetry",
        "com.google.android.marvin.talkback",
        "com.android.vending",
        "com.google.android.googlequicksearchbox"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_vpn)

        appVpnStorage = AppVpnStorage(this)
        selectedPackages.addAll(appVpnStorage.getSelectedPackages())

        rvApps = findViewById(R.id.rvApps)
        btnSave = findViewById(R.id.btnSave)

        rvApps.layoutManager = LinearLayoutManager(this)

        if (!hasUsageStatsPermission()) {
            showPermissionDialog()
        }

        loadApps()

        btnSave.setOnClickListener {
            appVpnStorage.setSelectedPackages(selectedPackages)
            val enabled = selectedPackages.isNotEmpty()
            appVpnStorage.setEnabled(enabled)
            if (enabled) {
                AppMonitorService.start(this)
                Toast.makeText(this, "App VPN enabled for " + selectedPackages.size + " apps", Toast.LENGTH_SHORT).show()
            } else {
                AppMonitorService.stop(this)
                Toast.makeText(this, "App VPN disabled", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN, null)
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveList: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)

            val appList = resolveList
                .filter { it.activityInfo.packageName != packageName } // исключаем Config
                .filter { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    if (pkg in systemTrash) return@filter false
                    if (pkg in popularApps) return@filter true
                    // Остальные: показываем только если НЕ системное
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    } catch (e: Exception) {
                        false
                    }
                }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .map { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    AppInfo(
                        packageName = pkg,
                        appName = resolveInfo.loadLabel(pm).toString(),
                        icon = resolveInfo.loadIcon(pm),
                        isSelected = selectedPackages.contains(pkg)
                    )
                }

            apps.clear()
            apps.addAll(appList)

            withContext(Dispatchers.Main) {
                rvApps.adapter = AppListAdapter(apps) { app, isChecked ->
                    if (isChecked) {
                        selectedPackages.add(app.packageName)
                    } else {
                        selectedPackages.remove(app.packageName)
                    }
                }
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("App VPN needs Usage Access permission to detect when selected apps are opened. Please enable it in settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
}
