package com.config.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVpnActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var rvPopular: RecyclerView
    private lateinit var btnSave: Button
    private lateinit var etSearch: EditText
    private lateinit var tvCounter: TextView
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var progressBar: ProgressBar
    private lateinit var appVpnStorage: AppVpnStorage

    private val allApps = mutableListOf<AppInfo>()
    private val filteredApps = mutableListOf<AppInfo>()
    private val popularApps = mutableListOf<PopularApp>()
    private val selectedPackages = mutableSetOf<String>()

    private var isIncludeMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_vpn)

        appVpnStorage = AppVpnStorage(this)

        rvApps = findViewById(R.id.rvApps)
        rvPopular = findViewById(R.id.rvPopular)
        btnSave = findViewById(R.id.btnSave)
        etSearch = findViewById(R.id.etSearch)
        tvCounter = findViewById(R.id.tvCounter)
        toggleMode = findViewById(R.id.toggleMode)
        progressBar = findViewById(R.id.progressBar)

        rvApps.layoutManager = LinearLayoutManager(this)
        rvPopular.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        if (!hasUsageStatsPermission()) {
            showPermissionDialog()
            return
        }

        val savedSelected = appVpnStorage.getSelectedPackages()
        val savedExcluded = appVpnStorage.getExcludedPackages()
        isIncludeMode = savedExcluded.isEmpty()
        if (isIncludeMode) selectedPackages.addAll(savedSelected)
        else selectedPackages.addAll(savedExcluded)

        toggleMode.check(if (isIncludeMode) R.id.btnModeInclude else R.id.btnModeExclude)
        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isIncludeMode = (checkedId == R.id.btnModeInclude)
                updateCounter()
                updatePopularVisuals()
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSave.setOnClickListener {
            if (isIncludeMode) {
                appVpnStorage.setSelectedPackages(selectedPackages)
                appVpnStorage.setExcludedPackages(emptySet())
            } else {
                appVpnStorage.setSelectedPackages(emptySet())
                appVpnStorage.setExcludedPackages(selectedPackages)
            }
            val enabled = selectedPackages.isNotEmpty()
            appVpnStorage.setEnabled(enabled)

            if (enabled) {
                AppMonitorService.start(this)
                if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                    autoConnectVpn()
                }
                val modeText = if (isIncludeMode) "через VPN" else "обход VPN"
                Toast.makeText(this, "Сохранено: ${selectedPackages.size} ($modeText)", Toast.LENGTH_SHORT).show()
            } else {
                AppMonitorService.stop(this)
                Toast.makeText(this, "App VPN отключен", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        setupPopularApps()
        loadAppsOptimized()
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

    private fun setupPopularApps() {
        val pm = packageManager
        val popularData = listOf(
            "com.google.android.youtube" to "YouTube",
            "org.telegram.messenger" to "Telegram",
            "com.instagram.android" to "Instagram",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.whatsapp" to "WhatsApp",
            "com.android.chrome" to "Chrome",
            "com.twitter.android" to "Twitter",
            "com.facebook.katana" to "Facebook",
            "com.vkontakte.android" to "VK",
            "com.discord" to "Discord",
            "com.spotify.music" to "Spotify"
        )

        popularApps.clear()
        for ((pkg, name) in popularData) {
            val installed = try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (e: Exception) { false }

            val icon = if (installed) {
                try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
            } else null

            popularApps.add(PopularApp(pkg, name, icon, installed))
        }

        rvPopular.adapter = PopularAppAdapter(popularApps, selectedPackages) { app, isChecked ->
            if (!app.isInstalled) {
                Toast.makeText(this, "${app.appName} не установлен", Toast.LENGTH_SHORT).show()
                return@PopularAppAdapter
            }
            if (isChecked) selectedPackages.add(app.packageName)
            else selectedPackages.remove(app.packageName)
            updateCounter()
            updateMainListSelection(app.packageName, isChecked)
        }
    }

    private fun loadAppsOptimized() {
        progressBar.visibility = View.VISIBLE

        val cached = appVpnStorage.getCachedAppList()
        if (cached != null) {
            try {
                val arr = org.json.JSONArray(cached)
                allApps.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pkg = obj.getString("pkg")
                    allApps.add(AppInfo(
                        packageName = pkg,
                        appName = obj.getString("name"),
                        icon = null,
                        isSelected = selectedPackages.contains(pkg)
                    ))
                }
                filteredApps.clear()
                filteredApps.addAll(allApps)
                setupAdapter()
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                updateCounter()
            } catch (e: Exception) {
                // corrupted cache
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val appList = mutableListOf<AppInfo>()
            val jsonArr = org.json.JSONArray()

            try {
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (appInfo in packages) {
                    val pkg = appInfo.packageName
                    if (pkg == packageName) continue
                    if (pm.getLaunchIntentForPackage(pkg) == null) continue

                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                    val isSelected = selectedPackages.contains(pkg)

                    appList.add(AppInfo(pkg, label, icon, isSelected))

                    val obj = org.json.JSONObject()
                    obj.put("pkg", pkg)
                    obj.put("name", label)
                    jsonArr.put(obj)
                }
            } catch (e: Exception) {
                android.util.Log.e("AppVpn", "Load error: ${e.message}")
            }

            appList.sortBy { it.appName.lowercase() }
            appVpnStorage.cacheAppList(jsonArr.toString())

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(appList)
                filterApps(etSearch.text.toString())
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                updateCounter()
            }
        }
    }

    private fun setupAdapter() {
        rvApps.adapter = AppListAdapter(filteredApps) { app, isChecked ->
            if (isChecked) selectedPackages.add(app.packageName)
            else selectedPackages.remove(app.packageName)
            allApps.find { it.packageName == app.packageName }?.isSelected = isChecked
            updateCounter()
            updatePopularVisuals()
        }
    }

    private fun filterApps(query: String) {
        val q = query.lowercase().trim()
        filteredApps.clear()
        if (q.isEmpty()) {
            filteredApps.addAll(allApps)
        } else {
            filteredApps.addAll(allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        rvApps.adapter?.notifyDataSetChanged()
    }

    private fun updateCounter() {
        val mode = if (isIncludeMode) "через VPN" else "обход VPN"
        tvCounter.text = "Выбрано: ${selectedPackages.size} ($mode)"
    }

    private fun updatePopularVisuals() {
        rvPopular.adapter?.notifyDataSetChanged()
    }

    private fun updateMainListSelection(pkg: String, isChecked: Boolean) {
        val app = allApps.find { it.packageName == pkg }
        if (app != null) {
            app.isSelected = isChecked
            val idx = filteredApps.indexOfFirst { it.packageName == pkg }
            if (idx >= 0) rvApps.adapter?.notifyItemChanged(idx)
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