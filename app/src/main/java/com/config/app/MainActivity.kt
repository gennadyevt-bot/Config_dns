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
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var vpnManager: VpnManager
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var serverStorage: ServerStorage
    private lateinit var tvStatus: TextView
    private lateinit var rvServers: RecyclerView
    private lateinit var tvTrafficDown: TextView
    private lateinit var tvTrafficUp: TextView
    private lateinit var ivMenu: ImageView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

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

        vpnManager = VpnManager.getInstance(this)
        serverStorage = ServerStorage(this)

        tvStatus = findViewById(R.id.tvStatus)
        rvServers = findViewById(R.id.rvServers)
        tvTrafficDown = findViewById(R.id.tvTrafficDown)
        tvTrafficUp = findViewById(R.id.tvTrafficUp)
        ivMenu = findViewById(R.id.ivMenu)
        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)
        navView.itemIconTintList = null

        requestNotificationPermission()
        loadServers()
        setupRecyclerView()
        setupVpnCallbacks()
        updateUiState(VpnStatus.DISCONNECTED)

        ivMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.navAppVpn -> startActivity(android.content.Intent(this, AppVpnActivity::class.java))
                R.id.navDomainVpn -> startActivity(android.content.Intent(this, DomainVpnActivity::class.java))
                R.id.navBackup -> showBackupDialog()
                R.id.navAutoConnect -> showAutoConnectDialog()
                R.id.navAbout -> Toast.makeText(this, "DNS config v4.3.2 | WireGuard + Kotlin", Toast.LENGTH_SHORT).show()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        val autoConnect = AutoConnectStorage(this)
        if (autoConnect.isEnabled()) {
            val validServer = servers.firstOrNull { hasValidConfig(it) }
            validServer?.let {
                selectedServer = it
                requestVpnPermissionAndConnect(it)
            }
        }

        val appVpnStorage = AppVpnStorage(this)
        if (appVpnStorage.isEnabled() && appVpnStorage.getSelectedPackages().isNotEmpty()) {
            AppMonitorService.start(this)
        }
    }

    private fun loadServers() {
        servers.clear()
        val saved = serverStorage.loadServers()
        if (saved.isEmpty()) {
            servers.add(ServerInfo(
                id = "slot_0",
                name = "VPNJantit Premium USA",
                interfaceAddress = "192.168.6.75/32",
                interfaceDns = "1.1.1.1, 8.8.8.8",
                interfacePrivateKey = "WLxO4K6sMjbqK4xclRnSkwnUzBMbTHhMoITliWk2zHs=",
                peerPublicKey = "5EhTY/DjbqjL4M7v3KaMOl84FVt/ZtOnAKIGpQy4GSY=",
                peerEndpoint = "premiusa2.vpnjantit.com:1024",
                peerAllowedIPs = "0.0.0.0/0",
                peerPersistentKeepalive = "25",
                jc = "0", jmin = "0", jmax = "0",
                s1 = "0", s2 = "0",
                h1 = "0", h2 = "0", h3 = "0", h4 = "0"
            ))
            repeat(5) { index ->
                servers.add(ServerInfo(
                    id = "slot_${index + 1}",
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
                val jc = etJc.text.toString().trim().ifEmpty { "0" }
                val jmin = etJmin.text.toString().trim().ifEmpty { "0" }
                val jmax = etJmax.text.toString().trim().ifEmpty { "0" }
                val s1 = etS1.text.toString().trim().ifEmpty { "0" }
                val s2 = etS2.text.toString().trim().ifEmpty { "0" }
                val h1 = etH1.text.toString().trim().ifEmpty { "0" }
                val h2 = etH2.text.toString().trim().ifEmpty { "0" }
                val h3 = etH3.text.toString().trim().ifEmpty { "0" }
                val h4 = etH4.text.toString().trim().ifEmpty { "0" }

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
        etEndpoint.setText(server.peerEndpoint)
        etPrivateKey.setText(server.interfacePrivateKey)
        etPublicKey.setText(server.peerPublicKey)
        etAddress.setText(server.interfaceAddress)
        etJc.setText(server.jc)
        etJmin.setText(server.jmin)
        etJmax.setText(server.jmax)
        etS1.setText(server.s1)
        etS2.setText(server.s2)
        etH1.setText(server.h1)
        etH2.setText(server.h2)
        etH3.setText(server.h3)
        etH4.setText(server.h4)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val endpoint = etEndpoint.text.toString().trim()
                val privateKey = etPrivateKey.text.toString().trim()
                val publicKey = etPublicKey.text.toString().trim()
                val address = etAddress.text.toString().trim().ifEmpty { "192.168.6.54/32" }
                val jc = etJc.text.toString().trim().ifEmpty { "0" }
                val jmin = etJmin.text.toString().trim().ifEmpty { "0" }
                val jmax = etJmax.text.toString().trim().ifEmpty { "0" }
                val s1 = etS1.text.toString().trim().ifEmpty { "0" }
                val s2 = etS2.text.toString().trim().ifEmpty { "0" }
                val h1 = etH1.text.toString().trim().ifEmpty { "0" }
                val h2 = etH2.text.toString().trim().ifEmpty { "0" }
                val h3 = etH3.text.toString().trim().ifEmpty { "0" }
                val h4 = etH4.text.toString().trim().ifEmpty { "0" }

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
                Toast.makeText(this, "Config updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Delete") { _, _ ->
                servers[position] = ServerInfo(
                    id = "slot_$position",
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
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun updateUiState(status: VpnStatus) {
        tvStatus.text = when (status) {
            VpnStatus.CONNECTED -> "Connected"
            VpnStatus.CONNECTING -> "Connecting…"
            VpnStatus.DISCONNECTED -> "Disconnected"
            VpnStatus.DISCONNECTING -> "Disconnecting…"
            VpnStatus.SWITCHING -> "Switching…"
            VpnStatus.ERROR -> "Error"
        }
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
