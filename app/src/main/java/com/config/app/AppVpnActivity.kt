package com.config.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
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
    private lateinit var etSearch: EditText
    private lateinit var appVpnStorage: AppVpnStorage
    private val allApps = mutableListOf<AppInfo>()
    private val displayedApps = mutableListOf<AppInfo>()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_vpn)

        appVpnStorage = AppVpnStorage(this)
        selectedPackages.addAll(appVpnStorage.getSelectedPackages())

        rvApps = findViewById(R.id.rvApps)
        btnSave = findViewById(R.id.btnSave)
        etSearch = findViewById(R.id.etSearch)

        rvApps.layoutManager = LinearLayoutManager(this)

        if (!hasUsageStatsPermission()) {
            showPermissionDialog()
        }

        loadApps()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

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
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                // Только пользовательские (non-system) приложения, исключая сам Config
                .filter {
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    it.packageName != packageName
                }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

            val appList = installedApps.map { appInfo ->
                val pkg = appInfo.packageName
                AppInfo(
                    packageName = pkg,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isSelected = selectedPackages.contains(pkg)
                )
            }

            allApps.clear()
            allApps.addAll(appList)
            displayedApps.clear()
            displayedApps.addAll(appList)

            withContext(Dispatchers.Main) {
                rvApps.adapter = AppListAdapter(displayedApps) { app, isChecked ->
                    if (isChecked) {
                        selectedPackages.add(app.packageName)
                    } else {
                        selectedPackages.remove(app.packageName)
                    }
                }
            }
        }
    }

    private fun filterApps(query: String) {
        val lower = query.lowercase()
        displayedApps.clear()
        if (lower.isEmpty()) {
            displayedApps.addAll(allApps)
        } else {
            displayedApps.addAll(allApps.filter {
                it.appName.lowercase().contains(lower) ||
                it.packageName.lowercase().contains(lower)
            })
        }
        rvApps.adapter?.notifyDataSetChanged()
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
