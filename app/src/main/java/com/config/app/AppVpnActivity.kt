package com.config.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
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
    private lateinit var btnSave: Button
    private lateinit var etSearch: EditText
    private lateinit var tvCounter: TextView
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var progressBar: ProgressBar
    private lateinit var spServer: Spinner
    private lateinit var appVpnStorage: AppVpnStorage
    private var serversList = listOf<ServerInfo>()

    private val allApps = mutableListOf<AppInfo>()
    private val filteredApps = mutableListOf<AppInfo>()
    private val selectedPackages = mutableSetOf<String>()

    private var isIncludeMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_vpn)

        appVpnStorage = AppVpnStorage(this)

        rvApps = findViewById(R.id.rvApps)
        btnSave = findViewById(R.id.btnSave)
        etSearch = findViewById(R.id.etSearch)
        tvCounter = findViewById(R.id.tvCounter)
        toggleMode = findViewById(R.id.toggleMode)
        progressBar = findViewById(R.id.progressBar)
        spServer = findViewById(R.id.spServer)

        rvApps.layoutManager = LinearLayoutManager(this)

        // Выбор конфига (сервера) для App VPN
        serversList = ServerStorage(this).loadServers()
        val serverNames = serversList.map { it.name.ifEmpty { it.peerEndpoint.ifEmpty { "Сервер" } } }
        val serverAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, serverNames)
        serverAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spServer.adapter = serverAdapter
        val savedServerId = appVpnStorage.getServerId()
        val savedIdx = serversList.indexOfFirst { it.id == savedServerId }
        spServer.setSelection(if (savedIdx >= 0) savedIdx else 0)

        if (!hasUsageStatsPermission()) {
            showPermissionDialog()
            return
        }

        val savedSelected = appVpnStorage.getSelectedPackages()
        val savedExcluded = appVpnStorage.getExcludedPackages()
        isIncludeMode = savedExcluded.isEmpty()

        if (isIncludeMode) {
            selectedPackages.addAll(savedSelected)
            toggleMode.check(R.id.btnModeInclude)
        } else {
            selectedPackages.addAll(savedExcluded)
            toggleMode.check(R.id.btnModeExclude)
        }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isIncludeMode = (checkedId == R.id.btnModeInclude)
                selectedPackages.clear()
                updateCounter()
                loadApps()
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

            val selIdx = spServer.selectedItemPosition
            if (serversList.isNotEmpty() && selIdx in serversList.indices) {
                appVpnStorage.setServerId(serversList[selIdx].id)
            }

            if (enabled) {
                AppMonitorService.start(this)
                if (VpnManager.globalStatus == VpnStatus.DISCONNECTED) {
                    autoConnectVpn()
                }
                val modeText = if (isIncludeMode) "через VPN" else "обход VPN"
                Toast.makeText(this, "Сохранено: ${selectedPackages.size} приложений ($modeText)", Toast.LENGTH_SHORT).show()
            } else {
                AppMonitorService.stop(this)
                Toast.makeText(this, "App VPN отключен", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        loadApps()
    }

    private fun autoConnectVpn() {
        val servers = ServerStorage(this).loadServers()
        val serverId = appVpnStorage.getServerId()
        // Сначала выбранный в App VPN конфиг, иначе первый валидный
        val validServer = servers.firstOrNull {
            it.id == serverId && it.interfacePrivateKey.isNotEmpty() && it.peerPublicKey.isNotEmpty() && it.peerEndpoint.isNotEmpty()
        } ?: servers.firstOrNull {
            it.interfacePrivateKey.isNotEmpty() && it.peerPublicKey.isNotEmpty() && it.peerEndpoint.isNotEmpty()
        }
        validServer?.let { server ->
            VpnManager.getInstance(this).connect(server)
        }
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val appList = mutableListOf<AppInfo>()

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
                    val isSelected = selectedPackages.contains(pkg)

                    appList.add(AppInfo(pkg, label, icon, isSelected))
                }
            } catch (e: Exception) {
                android.util.Log.e("AppVpn", "Load apps failed: ${e.message}", e)
            }

            appList.sortBy { it.appName.lowercase() }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(appList)
                filteredApps.clear()
                filteredApps.addAll(appList)
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                updateCounter()
                setupAdapter()
            }
        }
    }

    private fun setupAdapter() {
        rvApps.adapter = AppListAdapter(filteredApps) { app, isChecked ->
            if (isChecked) {
                selectedPackages.add(app.packageName)
            } else {
                selectedPackages.remove(app.packageName)
            }
            allApps.find { it.packageName == app.packageName }?.isSelected = isChecked
            updateCounter()
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
        tvCounter.text = "Выбрано: ${selectedPackages.size}"
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