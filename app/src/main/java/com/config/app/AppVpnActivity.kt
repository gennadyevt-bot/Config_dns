package com.config.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
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
    private lateinit var tvMode: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var rbVpnOn: RadioButton
    private lateinit var rbVpnOff: RadioButton
    private lateinit var appVpnStorage: AppVpnStorage
    private val apps = mutableListOf<AppInfo>()
    private val selectedPackages = mutableSetOf<String>()
    private val excludedPackages = mutableSetOf<String>()
    private var isVpnMode = true

    private val blacklist = setOf(
        "com.android.stk", "com.hihonor.android.clone", "com.hihonor.android.fmradio",
        "com.hihonor.photos", "com.hihonor.soundrecorder", "com.hihonor.systemmanager",
        "com.hihonor.gameassistant", "com.hihonor.magazine", "com.hihonor.detectrepair",
        "com.hihonor.android.pushagent", "com.hihonor.appmarket"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_vpn)

        appVpnStorage = AppVpnStorage(this)
        selectedPackages.addAll(appVpnStorage.getSelectedPackages())
        excludedPackages.addAll(appVpnStorage.getExcludedPackages())

        rvApps = findViewById(R.id.rvApps)
        btnSave = findViewById(R.id.btnSave)
        tvMode = findViewById(R.id.tvMode)
        radioGroup = findViewById(R.id.radioGroup)
        rbVpnOn = findViewById(R.id.rbVpnOn)
        rbVpnOff = findViewById(R.id.rbVpnOff)

        rvApps.layoutManager = LinearLayoutManager(this)

        if (!hasUsageStatsPermission()) {
            showPermissionDialog()
            return
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            isVpnMode = (checkedId == R.id.rbVpnOn)
            updateModeUI()
            loadApps()
        }

        updateModeUI()
        loadApps()

        btnSave.setOnClickListener {
            appVpnStorage.setSelectedPackages(selectedPackages)
            appVpnStorage.setExcludedPackages(excludedPackages)
            val enabled = selectedPackages.isNotEmpty() || excludedPackages.isNotEmpty()
            appVpnStorage.setEnabled(enabled)

            if (enabled) {
                AppMonitorService.start(this)
                if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                    autoConnectVpn()
                }
                Toast.makeText(this, "VPN ON: " + selectedPackages.size + ", VPN OFF: " + excludedPackages.size, Toast.LENGTH_SHORT).show()
            } else {
                AppMonitorService.stop(this)
                Toast.makeText(this, "App VPN disabled", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun autoConnectVpn() {
        val servers = ServerStorage(this).loadServers()
        val validServer = servers.firstOrNull {
            it.interfacePrivateKey.isNotEmpty() && it.peerPublicKey.isNotEmpty() && it.peerEndpoint.isNotEmpty()
        }
        validServer?.let { server ->
            VpnManager.getInstance(this).connect(server)
        }
    }

    private fun updateModeUI() {
        tvMode.text = if (isVpnMode) "Режим: ЧЕРЕЗ VPN" else "Режим: БЕЗ VPN"
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val appList = mutableListOf<AppInfo>()
            var total = 0
            var hasLauncher = 0
            var noLauncher = 0
            var errors = 0

            try {
                val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
                total = packages.size
                android.util.Log.d("AppVpn", "Total packages: $total")

                for (pkgInfo in packages) {
                    val pkg = pkgInfo.packageName
                    try {
                        if (pkg == packageName) continue
                        if (pkg in blacklist) continue

                        val appInfo = pkgInfo.applicationInfo ?: continue

                        // Проверяем, что у приложения есть launcher activity
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        if (launchIntent == null) {
                            noLauncher++
                            continue
                        }
                        hasLauncher++

                        val label = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        val isSelected = if (isVpnMode) selectedPackages.contains(pkg) else excludedPackages.contains(pkg)

                        appList.add(AppInfo(
                            packageName = pkg,
                            appName = label,
                            icon = icon,
                            isSelected = isSelected
                        ))
                    } catch (e: Exception) {
                        errors++
                        android.util.Log.w("AppVpn", "Skip package $pkg: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppVpn", "Fatal loadApps error: ${e.message}", e)
                // Fallback: показываем все без фильтра getLaunchIntentForPackage
                try {
                    val fallback = pm.getInstalledPackages(0)
                    for (fbPkgInfo in fallback) {
                        val fbPkg = fbPkgInfo.packageName
                        try {
                            if (fbPkg == packageName || fbPkg in blacklist) continue
                            val appInfo = fbPkgInfo.applicationInfo ?: continue
                            val label = pm.getApplicationLabel(appInfo).toString()
                            val icon = pm.getApplicationIcon(appInfo)
                            val isSelected = if (isVpnMode) selectedPackages.contains(fbPkg) else excludedPackages.contains(fbPkg)
                            appList.add(AppInfo(fbPkg, label, icon, isSelected))
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            appList.sortBy { it.appName.lowercase() }
            apps.clear()
            apps.addAll(appList)
            android.util.Log.d("AppVpn", "Loaded: ${apps.size} (total=$total, launcher=$hasLauncher, noLauncher=$noLauncher, err=$errors)")

            withContext(Dispatchers.Main) {
                Toast.makeText(this@AppVpnActivity, "Приложений: ${apps.size}", Toast.LENGTH_SHORT).show()
                rvApps.adapter = AppListAdapter(apps) { app, isChecked ->
                    if (isVpnMode) {
                        if (isChecked) selectedPackages.add(app.packageName)
                        else selectedPackages.remove(app.packageName)
                    } else {
                        if (isChecked) excludedPackages.add(app.packageName)
                        else excludedPackages.remove(app.packageName)
                    }
                }
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("App VPN needs Usage Access permission. Please enable it in settings.")
            .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}