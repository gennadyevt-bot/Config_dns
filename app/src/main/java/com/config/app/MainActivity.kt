package com.config.app

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var vpnManager: VpnManager
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var serverStorage: ServerStorage
    private lateinit var tvStatus: TextView
    private lateinit var rvServers: RecyclerView
    private lateinit var tvTrafficDown: TextView
    private lateinit var tvTrafficUp: TextView
    private lateinit var ivMenu: ImageView

    private var selectedServer: ServerInfo? = null
    private val servers = mutableListOf<ServerInfo>()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedServer?.let { connectToServer(it) }
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notifications disabled — background mode may be unstable", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vpnManager = VpnManager(this)
        serverStorage = ServerStorage(this)

        tvStatus = findViewById(R.id.tvStatus)
        rvServers = findViewById(R.id.rvServers)
        tvTrafficDown = findViewById(R.id.tvTrafficDown)
        tvTrafficUp = findViewById(R.id.tvTrafficUp)
        ivMenu = findViewById(R.id.ivMenu)

        requestNotificationPermission()
        loadServers()
        setupRecyclerView()
        setupVpnCallbacks()
        updateUiState(VpnStatus.DISCONNECTED)

        ivMenu.setOnClickListener {
            showMenuDialog()
        }
    }

    private fun loadServers() {
        servers.clear()
        val saved = serverStorage.loadServers()
        if (saved.isEmpty()) {
            // Create 6 empty slots
            repeat(6) { index ->
                servers.add(ServerInfo(
                    id = "slot_$index",
                    name = "Empty Slot",
                    interfaceAddress = "",
                    interfacePrivateKey = "",
                    peerPublicKey = "",
                    peerEndpoint = ""
                ))
            }
            serverStorage.saveServers(servers)
        } else {
            servers.addAll(saved)
        }
    }

    private fun setupRecyclerView() {
        serverAdapter = ServerAdapter(
            servers,
            onConnectClick = { server ->
                if (!hasValidConfig(server)) {
                    Toast.makeText(this, "Add config first via +", Toast.LENGTH_SHORT).show()
                    return@ServerAdapter
                }
                selectedServer = server
                requestVpnPermissionAndConnect(server)
            },
            onStopClick = { _ ->
                vpnManager.disconnect()
            },
            onAddClick = { server, position ->
                showAddServerDialog(server, position)
            },
            onLongPress = { server, position ->
                if (hasValidConfig(server)) {
                    showEditServerDialog(server, position)
                } else {
                    showAddServerDialog(server, position)
                }
            }
        )
        rvServers.layoutManager = LinearLayoutManager(this)
        rvServers.adapter = serverAdapter
    }

    private fun setupVpnCallbacks() {
        vpnManager.onStatusChanged = { status ->
            updateUiState(status)
            serverAdapter.setStatus(status)
        }
        vpnManager.onServerChanged = { server ->
            server?.let {
                serverAdapter.setSelectedServer(it.id)
            } ?: run {
                serverAdapter.setSelectedServer(null)
            }
        }
    }

    private fun hasValidConfig(server: ServerInfo): Boolean {
        return server.interfacePrivateKey.isNotEmpty() &&
                server.peerPublicKey.isNotEmpty() &&
                server.peerEndpoint.isNotEmpty()
    }

    private fun requestVpnPermissionAndConnect(server: ServerInfo) {
        val intent = vpnManager.getPrepareIntent(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            connectToServer(server)
        }
    }

    private fun connectToServer(server: ServerInfo) {
        vpnManager.connect(server)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> { }
                shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(this, "Notifications needed for stable background operation", Toast.LENGTH_LONG).show()
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showAddServerDialog(server: ServerInfo, position: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_server, null)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etEndpoint = view.findViewById<EditText>(R.id.etEndpoint)
        val etPrivateKey = view.findViewById<EditText>(R.id.etPrivateKey)
        val etPublicKey = view.findViewById<EditText>(R.id.etPublicKey)
        val etAddress = view.findViewById<EditText>(R.id.etAddress)
        val etJc = view.findViewById<EditText>(R.id.etJc)
        val etJmin = view.findViewById<EditText>(R.id.etJmin)
        val etJmax = view.findViewById<EditText>(R.id.etJmax)
        val etS1 = view.findViewById<EditText>(R.id.etS1)
        val etS2 = view.findViewById<EditText>(R.id.etS2)
        val etH1 = view.findViewById<EditText>(R.id.etH1)
        val etH2 = view.findViewById<EditText>(R.id.etH2)
        val etH3 = view.findViewById<EditText>(R.id.etH3)
        val etH4 = view.findViewById<EditText>(R.id.etH4)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().trim()
                val endpoint = etEndpoint.text.toString().trim()
                val privateKey = etPrivateKey.text.toString().trim()
                val publicKey = etPublicKey.text.toString().trim()
                val address = etAddress.text.toString().trim().ifEmpty { "192.168.6.54/32" }
                val jc = etJc.text.toString().trim().ifEmpty { "5" }
                val jmin = etJmin.text.toString().trim().ifEmpty { "50" }
                val jmax = etJmax.text.toString().trim().ifEmpty { "1000" }
                val s1 = etS1.text.toString().trim().ifEmpty { "50" }
                val s2 = etS2.text.toString().trim().ifEmpty { "100" }
                val h1 = etH1.text.toString().trim().ifEmpty { "1" }
                val h2 = etH2.text.toString().trim().ifEmpty { "2" }
                val h3 = etH3.text.toString().trim().ifEmpty { "3" }
                val h4 = etH4.text.toString().trim().ifEmpty { "4" }

                if (name.isEmpty() || endpoint.isEmpty() || privateKey.isEmpty() || publicKey.isEmpty()) {
                    Toast.makeText(this, "Fill all required fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updated = server.copy(
                    name = name,
                    interfaceAddress = address,
                    interfaceDns = "1.1.1.1, 8.8.8.8",
                    interfacePrivateKey = privateKey,
                    peerPublicKey = publicKey,
                    peerEndpoint = endpoint,
                    jc = jc,
                    jmin = jmin,
                    jmax = jmax,
                    s1 = s1,
                    s2 = s2,
                    h1 = h1,
                    h2 = h2,
                    h3 = h3,
                    h4 = h4
                )
                servers[position] = updated
                serverAdapter.notifyItemChanged(position)
                serverStorage.saveServers(servers)
                Toast.makeText(this, "Config saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditServerDialog(server: ServerInfo, position: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_server, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvDialogTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvDialogSubtitle)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etEndpoint = view.findViewById<EditText>(R.id.etEndpoint)
        val etPrivateKey = view.findViewById<EditText>(R.id.etPrivateKey)
        val etPublicKey = view.findViewById<EditText>(R.id.etPublicKey)
        val etPresharedKey = view.findViewById<EditText>(R.id.etPresharedKey)
        val etAddress = view.findViewById<EditText>(R.id.etAddress)
        val etJc = view.findViewById<EditText>(R.id.etJc)
        val etJmin = view.findViewById<EditText>(R.id.etJmin)
        val etJmax = view.findViewById<EditText>(R.id.etJmax)
        val etS1 = view.findViewById<EditText>(R.id.etS1)
        val etS2 = view.findViewById<EditText>(R.id.etS2)
        val etH1 = view.findViewById<EditText>(R.id.etH1)
        val etH2 = view.findViewById<EditText>(R.id.etH2)
        val etH3 = view.findViewById<EditText>(R.id.etH3)
        val etH4 = view.findViewById<EditText>(R.id.etH4)

        tvTitle.text = "Edit Config"
        tvSubtitle.text = server.name

        etName.setText(server.name)
        if (server.peerEndpoint.isNotEmpty()) etEndpoint.setText(server.peerEndpoint)
        if (server.interfacePrivateKey.isNotEmpty()) etPrivateKey.setText(server.interfacePrivateKey)
        if (server.peerPublicKey.isNotEmpty()) etPublicKey.setText(server.peerPublicKey)
        if (server.peerPresharedKey.isNotEmpty()) etPresharedKey.setText(server.peerPresharedKey)
        if (server.interfaceAddress.isNotEmpty()) etAddress.setText(server.interfaceAddress)
        etJc.setText(server.jc)
        etJmin.setText(server.jmin)
        etJmax.setText(server.jmax)
        etS1.setText(server.s1)
        etS2.setText(server.s2)
        etH1.setText(server.h1)
        etH2.setText(server.h2)
        etH3.setText(server.h3)
        etH4.setText(server.h4)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val endpoint = etEndpoint.text.toString().trim()
                val privateKey = etPrivateKey.text.toString().trim()
                val publicKey = etPublicKey.text.toString().trim()
                val presharedKey = etPresharedKey.text.toString().trim()
                val address = etAddress.text.toString().trim().ifEmpty { "192.168.6.54/32" }
                val jc = etJc.text.toString().trim().ifEmpty { "5" }
                val jmin = etJmin.text.toString().trim().ifEmpty { "50" }
                val jmax = etJmax.text.toString().trim().ifEmpty { "1000" }
                val s1 = etS1.text.toString().trim().ifEmpty { "50" }
                val s2 = etS2.text.toString().trim().ifEmpty { "100" }
                val h1 = etH1.text.toString().trim().ifEmpty { "1" }
                val h2 = etH2.text.toString().trim().ifEmpty { "2" }
                val h3 = etH3.text.toString().trim().ifEmpty { "3" }
                val h4 = etH4.text.toString().trim().ifEmpty { "4" }

                if (endpoint.isEmpty() || privateKey.isEmpty() || publicKey.isEmpty()) {
                    Toast.makeText(this, "Fill required fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val updated = server.copy(
                    name = name.ifEmpty { server.name },
                    peerEndpoint = endpoint,
                    interfacePrivateKey = privateKey,
                    peerPublicKey = publicKey,
                    peerPresharedKey = presharedKey,
                    interfaceAddress = address,
                    jc = jc,
                    jmin = jmin,
                    jmax = jmax,
                    s1 = s1,
                    s2 = s2,
                    h1 = h1,
                    h2 = h2,
                    h3 = h3,
                    h4 = h4
                )
                servers[position] = updated
                serverAdapter.notifyItemChanged(position)
                serverStorage.saveServers(servers)
                Toast.makeText(this, "Config saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Delete") { _, _ ->
            servers[position] = ServerInfo(
                id = server.id,
                name = "Empty Slot",
                interfaceAddress = "",
                interfacePrivateKey = "",
                peerPublicKey = "",
                peerEndpoint = ""
            )
            serverAdapter.notifyItemChanged(position)
            serverStorage.saveServers(servers)
            Toast.makeText(this, "Config deleted", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun updateUiState(status: VpnStatus) {
        StopVpnWidget.updateWidget(this, status)
        when (status) {
            VpnStatus.CONNECTED -> {
                tvStatus.text = "Active"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                startTrafficMonitor()
            }
            VpnStatus.CONNECTING -> {
                tvStatus.text = "Connecting..."
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            }
            VpnStatus.SWITCHING -> {
                tvStatus.text = "Switching..."
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            }
            VpnStatus.DISCONNECTING -> {
                tvStatus.text = "Disconnecting..."
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            }
            else -> {
                tvStatus.text = "Disconnected"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                stopTrafficMonitor()
            }
        }
    }

    private var trafficHandler: android.os.Handler? = null
    private var trafficRunnable: Runnable? = null

    private fun startTrafficMonitor() {
        trafficHandler = android.os.Handler(android.os.Looper.getMainLooper())
        trafficRunnable = object : Runnable {
            override fun run() {
                val stats = vpnManager.getTrafficStats()
                tvTrafficDown.text = "↓ ${formatBytes(stats.rxBytes)}/s"
                tvTrafficUp.text = "↑ ${formatBytes(stats.txBytes)}/s"
                trafficHandler?.postDelayed(this, 1000)
            }
        }
        trafficHandler?.post(trafficRunnable!!)
    }

    private fun stopTrafficMonitor() {
        trafficRunnable?.let { trafficHandler?.removeCallbacks(it) }
        trafficHandler = null
        trafficRunnable = null
        tvTrafficDown.text = "↓ 0 B/s"
        tvTrafficUp.text = "↑ 0 B/s"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnManager.getStatus() == VpnStatus.CONNECTED) {
            vpnManager.disconnect()
        }
    }

    private fun showMenuDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_menu, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btnBackup).setOnClickListener {
            showBackupDialog()
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnAutoConnect).setOnClickListener {
            showAutoConnectDialog()
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btnAbout).setOnClickListener {
            Toast.makeText(this, "Config v1.0.0 | AmneziaWG + Kotlin", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showBackupDialog() {
        val backupManager = ServerBackupManager(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Backup")
            .setMessage("Export or import configs?")
            .setPositiveButton("Export") { _, _ ->
                backupManager.shareBackup()
                Toast.makeText(this, "Configs exported", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Import") { _, _ ->
                Toast.makeText(this, "Import: select .json file", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showAutoConnectDialog() {
        val storage = AutoConnectStorage(this)
        val enabled = storage.isEnabled()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Auto Connect")
            .setMessage("Auto-connect on app open.\n\nStatus: ${if (enabled) "ON" else "OFF"}")
            .setPositiveButton(if (enabled) "Disable" else "Enable") { _, _ ->
                storage.setEnabled(!enabled)
                Toast.makeText(this, "Auto Connect: ${if (!enabled) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
