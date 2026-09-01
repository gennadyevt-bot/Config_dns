package com.config.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import kotlinx.coroutines.Job
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
    private var loadJob: Job? = null

    private val blacklist = setOf(
        "com.android.stk", "com.hihonor.android.clone", "com.hihonor.android.fmradio",
        "com.hihonor.photos", "com.hihonor.soundrecorder", "com.hihonor.systemmanager",
        "com.hihonor.gameassistant", "com.hihonor.magazine", "com.hihonor.detectrepair",
        "com.hihonor.android.pushagent", "com.hihonor.appmarket", "com.google.android.gms",
        "com.google.android.modulemetadata", "com.google.android.networkstack",
        "com.google.android.tts", "com.google.android.apps.wellbeing",
        "com.google.mainline.adservices", "com.google.mainline.telemetry",
        "com.google.android.marvin.talkback", "com.google.android.googlequicksearchbox",
        "com.android.settings", "com.android.dialer", "com.android.contacts",
        "com.android.messaging", "com.android.calendar", "com.android.camera",
        "com.android.calculator2", "com.android.documentsui", "com.android.packageinstaller"
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

        // Устанавливаем начальное состояние
        rbVpnOn.isChecked = true
        updateModeUI()
        loadApps()

        btnSave.setOnClickListener {
            appVpnStorage.setSelectedPackages(selectedPackages)
            appVpnStorage.setExcludedPackages(excludedPackages)
            val enabled = selectedPackages.isNotEmpty() || excludedPackages.isNotEmpty()
            appVpnStorage.setEnabled(enabled)
            if (enabled) {
                AppMonitorService.start(this)
                val msg = "VPN ON: " + selectedPackages.size + ", VPN OFF: " + excludedPackages.size
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } else {
                AppMonitorService.stop(this)
                Toast.makeText(this, "App VPN disabled", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun updateModeUI() {
        if (isVpnMode) {
            tvMode.text = "Режим: ЧЕРЕЗ VPN (выбранные приложения включают VPN)"
        } else {
            tvMode.text = "Режим: БЕЗ VPN (выбранные приложения отключают VPN)"
        }
    }

    private fun loadApps() {
        // Отменяем предыдущую загрузку
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN, null)
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER)

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PackageManager.MATCH_ALL
            } else {
                PackageManager.GET_META_DATA
            }
            val resolveList: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, flags)

            val appList = resolveList
                .filter { it.activityInfo.packageName != packageName }
                .filter { it.activityInfo.packageName !in blacklist }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .map { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    val isSelected = if (isVpnMode) selectedPackages.contains(pkg) else excludedPackages.contains(pkg)
                    AppInfo(
                        packageName = pkg,
                        appName = resolveInfo.loadLabel(pm).toString(),
                        icon = resolveInfo.loadIcon(pm),
                        isSelected = isSelected
                    )
                }

            // Проверяем, не отменена ли корутина
            if (!isActive) return@launch

            apps.clear()
            apps.addAll(appList)

            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
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

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
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
            .setMessage("App VPN needs Usage Access permission to detect when selected apps are opened. Please enable it in settings.")
            .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}