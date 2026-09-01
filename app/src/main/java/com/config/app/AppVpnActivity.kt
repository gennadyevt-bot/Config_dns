package com.config.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
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
    private lateinit var progressBar: ProgressBar
    private lateinit var appVpnStorage: AppVpnStorage
    private val apps = mutableListOf<AppInfo>()
    private val selectedPackages = mutableSetOf<String>()
    private val excludedPackages = mutableSetOf<String>()
    private var isVpnMode = true

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
        progressBar = findViewById(R.id.progressBar)

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
                Toast.makeText(this, "Сохранено: " + selectedPackages.size + " через VPN, " + excludedPackages.size + " без VPN", Toast.LENGTH_SHORT).show()
            } else {
                AppMonitorService.stop(this)
                Toast.makeText(this, "App VPN отключен", Toast.LENGTH_SHORT).show()
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
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val appList = mutableListOf<AppInfo>()

            try {
                val packages = pm.getInstalledPackages(0)
                android.util.Log.d("AppVpn", "Total packages: ${packages.size}")

                for (pkgInfo in packages) {
                    val pkg = pkgInfo.packageName
                    try {
                        if (pkg == packageName) continue

                        val appInfo = pkgInfo.applicationInfo ?: continue
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
                        android.util.Log.w("AppVpn", "Skip $pkg: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppVpn", "getInstalledPackages failed: ${e.message}", e)
            }

            // Fallback если пусто
            if (appList.isEmpty()) {
                try {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null)
                    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                    val seen = mutableSetOf<String>()
                    for (ri in resolveInfos) {
                        val pkg = ri.activityInfo.packageName
                        if (pkg == packageName || seen.contains(pkg)) continue
                        seen.add(pkg)
                        val label = ri.loadLabel(pm).toString()
                        val icon = ri.loadIcon(pm)
                        val isSelected = if (isVpnMode) selectedPackages.contains(pkg) else excludedPackages.contains(pkg)
                        appList.add(AppInfo(pkg, label, icon, isSelected))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AppVpn", "Fallback failed: ${e.message}", e)
                }
            }

            appList.sortBy { it.appName.lowercase() }
            apps.clear()
            apps.addAll(appList)
            android.util.Log.d("AppVpn", "Final count: ${apps.size}")

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                if (apps.isEmpty()) {
                    Toast.makeText(this@AppVpnActivity, "Список приложений пуст", Toast.LENGTH_LONG).show()
                }
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
            .setTitle("Разрешение")
            .setMessage("App VPN нужен доступ к Usage Stats. Включите в настройках.")
            .setPositiveButton("Настройки") { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}