package com.fireflisinfotech.ffitbt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.fireflisinfotech.ffitbt.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("ffitbt", Context.MODE_PRIVATE) }
    private val dbHelper by lazy { PrintDatabaseHelper(this) }
    private var logAdapter: PrintLogAdapter? = null

    private val BT_PERM_CODE = 101

    private val queueReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            loadPrintLogs()
        }
    }

    // ─────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSteps()
        setupTabs()
        setupWifiForm()
        setupOEMTroubleshooting()

        binding.btnScan.setOnClickListener { checkPermissionsAndScan() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnTestPrint.setOnClickListener { doTestPrint() }
        
        binding.btnOpenPrintSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
            } catch (e: Exception) {
                e.printStackTrace()
                snack("⚠️ System printing settings not found on this device.")
            }
        }
        
        binding.btnEnableServiceBanner.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
            } catch (e: Exception) {
                e.printStackTrace()
                snack("⚠️ System printing settings not found on this device.")
            }
        }

        binding.tvClearLogs.setOnClickListener {
            dbHelper.clearAllLogs()
            loadPrintLogs()
            snack("🧹 Print history cleared.")
        }

        updateUI()
    }

    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            queueReceiver,
            android.content.IntentFilter("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        loadPrintLogs()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(queueReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ─── Print Service Check ──────────────────────
    private fun isPrintServiceEnabled(): Boolean {
        // 1. Android 13+ (API 33+) official public check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val printManager = getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
            if (printManager != null) {
                try {
                    val comp = ComponentName(this, FFitPrintService::class.java)
                    return printManager.isPrintServiceEnabled(comp)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 2. Legacy / fallback secure settings check
        try {
            val enabledServicesSetting = Settings.Secure.getString(
                contentResolver,
                "enabled_print_services"
            )
            if (enabledServicesSetting != null) {
                val myService = ComponentName(this, FFitPrintService::class.java)
                return enabledServicesSetting.contains(myService.flattenToString()) ||
                       enabledServicesSetting.contains(myService.flattenToShortString()) ||
                       enabledServicesSetting.contains(packageName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    // ─── Setup TabLayout ──────────────────────────
    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Bluetooth Printer"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("WiFi Network Printer"))

        val savedMode = PrinterPrefs.getSavedMode(prefs)
        if (savedMode == "wifi") {
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(1))
            binding.layoutBluetooth.visibility = View.GONE
            binding.layoutWifi.visibility = View.VISIBLE
        } else {
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
            binding.layoutBluetooth.visibility = View.VISIBLE
            binding.layoutWifi.visibility = View.GONE
        }

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (tab.position == 1) {
                    PrinterPrefs.setConnectionMode(prefs, "wifi")
                    binding.layoutBluetooth.visibility = View.GONE
                    binding.layoutWifi.visibility = View.VISIBLE
                } else {
                    PrinterPrefs.setConnectionMode(prefs, "bluetooth")
                    binding.layoutBluetooth.visibility = View.VISIBLE
                    binding.layoutWifi.visibility = View.GONE
                }
                updateUI()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    // ─── WiFi Form Setup ─────────────────────────
    private fun setupWifiForm() {
        // Pre-populate fields
        binding.etWifiSsid.setText(PrinterPrefs.getSavedSsid(prefs))
        binding.etWifiIp.setText(PrinterPrefs.getSavedIp(prefs))
        binding.etWifiSubnet.setText(PrinterPrefs.getSavedSubnet(prefs))
        binding.etWifiPort.setText(PrinterPrefs.getSavedPort(prefs).toString())

        binding.btnSaveWifi.setOnClickListener {
            val ssid = binding.etWifiSsid.text.toString().trim()
            val ip = binding.etWifiIp.text.toString().trim()
            val subnet = binding.etWifiSubnet.text.toString().trim()
            val portStr = binding.etWifiPort.text.toString().trim()

            if (ip.isEmpty()) {
                snack("❌ IP Address cannot be empty")
                return@setOnClickListener
            }
            val port = portStr.toIntOrNull() ?: 9100

            PrinterPrefs.saveWifiSettings(prefs, ip, port, ssid, subnet)
            snack("⏳ Testing WiFi connection to $ip:$port...")
            binding.btnSaveWifi.isEnabled = false

            // Verify connection asynchronously
            Thread {
                val connected = WifiPrinter.connect(ip, port)
                runOnUiThread {
                    binding.btnSaveWifi.isEnabled = true
                    if (connected) {
                        snack("✅ WiFi Printer Configured & Reachable!")
                        updateUI()
                    } else {
                        snack("⚠️ Saved WiFi details, but connection failed. Make sure printer is ON.")
                        updateUI()
                    }
                }
            }.start()
        }
    }

    // ─── OEM Autostart setup ──────────────────────
    private fun setupOEMTroubleshooting() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("vivo") || manufacturer.contains("oppo") || manufacturer.contains("xiaomi")) {
            binding.layoutOEMTroubleshooting.visibility = View.VISIBLE
            binding.btnOpenOEMSettings.setOnClickListener { openOEMSettings() }
        } else {
            binding.layoutOEMTroubleshooting.visibility = View.GONE
        }
    }

    private fun openOEMSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        when {
            manufacturer.contains("xiaomi") -> {
                intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            manufacturer.contains("oppo") -> {
                intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            }
            manufacturer.contains("vivo") -> {
                intent.component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to Application Details Screen
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(fallback)
            } catch (ex: Exception) {
                ex.printStackTrace()
                snack("⚠️ Settings page not found.")
            }
        }
    }

    // ─── Permission Check ─────────────────────────
    private fun checkPermissionsAndScan() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (!hasPermission(Manifest.permission.BLUETOOTH))
                needed.add(Manifest.permission.BLUETOOTH)
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isEmpty()) {
            checkBtAndScan()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), BT_PERM_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BT_PERM_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkBtAndScan()
            } else {
                snack("❌ Bluetooth permission denied. Grant it in App Settings.")
            }
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ─── BT On Check ─────────────────────────────
    private fun checkBtAndScan() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = btManager.adapter

        when {
            adapter == null -> snack("❌ Bluetooth not supported on this device.")
            !adapter.isEnabled -> {
                snack("⚠️ Bluetooth is OFF. Please turn it ON and try again.")
                try {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            else -> showPrinterSheet()
        }
    }

    // ─── Printer Bottom Sheet ─────────────────────
    private fun showPrinterSheet() {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_printers, null)
        sheet.setContentView(view)

        val layoutLoading = view.findViewById<View>(R.id.layoutLoading)
        val layoutError   = view.findViewById<View>(R.id.layoutError)
        val layoutEmpty   = view.findViewById<View>(R.id.layoutEmpty)
        val rvPrinters    = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPrinters)
        val tvErrorMsg    = view.findViewById<TextView>(R.id.tvErrorMsg)
        val btnRefresh    = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRefresh)

        fun setState(state: String) {
            layoutLoading.visibility = if (state == "loading") View.VISIBLE else View.GONE
            layoutError.visibility   = if (state == "error")   View.VISIBLE else View.GONE
            layoutEmpty.visibility   = if (state == "empty")   View.VISIBLE else View.GONE
            rvPrinters.visibility    = if (state == "list")    View.VISIBLE else View.GONE
        }

        fun loadDevices() {
            setState("loading")
            Thread {
                try {
                    val devices = BluetoothPrinter.getPairedDevicesWithType(this)
                    runOnUiThread {
                        if (devices.isEmpty()) {
                            setState("empty")
                        } else {
                            setState("list")
                            rvPrinters.layoutManager = LinearLayoutManager(this)
                            rvPrinters.adapter = PrinterAdapter(devices) { selected ->
                                val (name, mac, isPrinter) = selected
                                if (!isPrinter) {
                                    // Warn user this may not work
                                    androidx.appcompat.app.AlertDialog.Builder(this, R.style.AlertDialogDark)
                                        .setTitle("⚠️ Not a Printer?")
                                        .setMessage("\"$name\" doesn't look like a thermal printer.\n\nConnecting to non-printer devices may not work.\n\nContinue anyway?")
                                        .setPositiveButton("Connect Anyway") { _, _ ->
                                            sheet.dismiss()
                                            connectToPrinter(name, mac)
                                        }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                } else {
                                    sheet.dismiss()
                                    connectToPrinter(name, mac)
                                }
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    runOnUiThread {
                        tvErrorMsg.text = "Bluetooth permission not granted.\nPlease allow Bluetooth access."
                        setState("error")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvErrorMsg.text = "Error: ${e.message}"
                        setState("error")
                    }
                }
            }.start()
        }

        btnRefresh.setOnClickListener { loadDevices() }
        loadDevices()
        sheet.show()
    }

    // ─── Connect ─────────────────────────────────
    private fun connectToPrinter(name: String, mac: String) {
        snack("⏳ Connecting to $name…")
        binding.btnScan.isEnabled = false
        Thread {
            val ok = BluetoothPrinter.connect(mac)
            runOnUiThread {
                binding.btnScan.isEnabled = true
                if (ok) {
                    PrinterPrefs.savePrinter(prefs, name, mac)
                    updateUI()
                    snack("✅ Connected to $name")
                } else {
                    snack("❌ Could not connect to $name. Make sure printer is ON and paired.")
                }
            }
        }.start()
    }

    // ─── Disconnect ───────────────────────────────
    private fun disconnect() {
        val mode = PrinterPrefs.getSavedMode(prefs)
        if (mode == "wifi") {
            WifiPrinter.disconnect()
            toast("WiFi Connection Reset")
        } else {
            BluetoothPrinter.disconnect()
            toast("Disconnected")
        }
        updateUI()
    }

    // ─── Test Print ───────────────────────────────
    private fun doTestPrint() {
        val mode = PrinterPrefs.getSavedMode(prefs)
        binding.btnTestPrint.isEnabled = false

        Thread {
            var connectionOk = false
            if (mode == "wifi") {
                val ip = PrinterPrefs.getSavedIp(prefs)
                val port = PrinterPrefs.getSavedPort(prefs)
                if (!WifiPrinter.isConnected) {
                    WifiPrinter.connect(ip, port)
                }
                connectionOk = WifiPrinter.isConnected
            } else {
                if (!BluetoothPrinter.isConnected) {
                    val mac = PrinterPrefs.getSavedMac(prefs)
                    if (mac.isNotEmpty()) {
                        BluetoothPrinter.connect(mac)
                    }
                }
                connectionOk = BluetoothPrinter.isConnected
            }

            if (!connectionOk) {
                runOnUiThread {
                    binding.btnTestPrint.isEnabled = true
                    snack("❌ Printer not reachable. Verify your connection/settings.")
                }
                return@Thread
            }

            val esc = EscPos()
            esc.add(EscPos.INIT)
            esc.add(EscPos.ALIGN_CENTER)
            esc.add(EscPos.DBLH_ON)
            esc.add(EscPos.BOLD_ON)
            esc.text("FFit BT")
            esc.add(EscPos.DBLH_OFF)
            esc.add(EscPos.BOLD_OFF)
            esc.separator()
            esc.text("Test Page")
            esc.text("")
            esc.add(EscPos.ALIGN_LEFT)

            if (mode == "wifi") {
                val ip = PrinterPrefs.getSavedIp(prefs)
                val port = PrinterPrefs.getSavedPort(prefs)
                esc.text("Mode     : WiFi Printer")
                esc.text("IP       : $ip:$port")
            } else {
                val name = PrinterPrefs.getSavedName(prefs)
                val mac = PrinterPrefs.getSavedMac(prefs)
                esc.text("Mode     : Bluetooth")
                esc.text("Name     : $name")
                esc.text("MAC      : $mac")
            }

            esc.text("Status   : OK - Connected")
            esc.text("Driver   : ESC/POS")
            esc.text("Paper    : 58mm Thermal")
            esc.text("App      : FFit BT v1.0")
            esc.separator()
            esc.add(EscPos.ALIGN_CENTER)
            esc.text("All Systems Functional!")
            esc.text("")
            // ESC t 0 = Switch to CP437 code page (0x03 = ♥ on thermal printers)
            esc.add(byteArrayOf(0x1B, 0x74, 0x00))
            esc.addRawText("made by raj ")
            esc.addByte(0x03)   // ♥ in CP437
            esc.addByte(0x0A)   // newline
            esc.add(byteArrayOf(0x1B, 0x64, 0x06)) // Feed 6 lines to avoid cut off
            esc.add(EscPos.CUT)

            val printOk = if (mode == "wifi") {
                val ok = WifiPrinter.send(esc.build())
                WifiPrinter.disconnect() // Disconnect after network print
                ok
            } else {
                BluetoothPrinter.send(esc.build())
            }

            runOnUiThread {
                binding.btnTestPrint.isEnabled = true
                if (printOk) snack("✅ Test page printed!")
                else snack("❌ Print failed — is printer ON?")
                updateUI()
            }
        }.start()
    }

    // ─── UI Update ────────────────────────────────
    private fun updateUI() {
        // Update print service warning banner & badge
        val serviceEnabled = isPrintServiceEnabled()
        if (serviceEnabled) {
            binding.layoutServiceWarning.visibility = View.GONE
            binding.tvServiceBadge.text = "● Service ON"
            binding.tvServiceBadge.setTextColor(getColor(R.color.green))
        } else {
            binding.layoutServiceWarning.visibility = View.VISIBLE
            binding.tvServiceBadge.text = "● Service OFF"
            binding.tvServiceBadge.setTextColor(getColor(R.color.amber))
        }

        val mode = PrinterPrefs.getSavedMode(prefs)
        if (mode == "wifi") {
            val ip = PrinterPrefs.getSavedIp(prefs)
            val port = PrinterPrefs.getSavedPort(prefs)
            val isConnected = WifiPrinter.isConnected

            if (isConnected) {
                binding.statusDot.setBackgroundResource(R.drawable.dot_green)
                binding.tvStatus.text = "WiFi Printer Active"
                binding.tvMac.text = "IP: $ip:$port"
                binding.tvMac.visibility = View.VISIBLE
                binding.btnDisconnect.visibility = View.VISIBLE
                binding.cardTestPrint.visibility = View.VISIBLE
            } else {
                binding.statusDot.setBackgroundResource(R.drawable.dot_red)
                binding.tvStatus.text = "WiFi Configured"
                binding.tvMac.text = "IP: $ip:$port (Not Connected)"
                binding.tvMac.visibility = View.VISIBLE
                binding.btnDisconnect.visibility = View.GONE
                binding.cardTestPrint.visibility = View.VISIBLE
            }
        } else {
            val connected = BluetoothPrinter.isConnected
            val name = PrinterPrefs.getSavedName(prefs)
            val mac  = PrinterPrefs.getSavedMac(prefs)

            if (connected) {
                binding.statusDot.setBackgroundResource(R.drawable.dot_green)
                binding.tvStatus.text = name.ifEmpty { "Printer Connected" }
                binding.tvMac.text = mac
                binding.tvMac.visibility = View.VISIBLE
                binding.btnDisconnect.visibility = View.VISIBLE
                binding.cardTestPrint.visibility = View.VISIBLE
            } else {
                binding.statusDot.setBackgroundResource(R.drawable.dot_red)
                binding.tvStatus.text = "No Printer Connected"
                binding.tvMac.visibility = View.GONE
                binding.btnDisconnect.visibility = View.GONE
                if (mac.isNotEmpty()) {
                    binding.tvMac.text = "$mac (Saved)"
                    binding.tvMac.visibility = View.VISIBLE
                    binding.cardTestPrint.visibility = View.VISIBLE
                } else {
                    binding.cardTestPrint.visibility = View.GONE
                }
            }
        }
    }

    // ─── Steps content ────────────────────────────
    private fun setupSteps() {
        val steps = listOf(
            Pair("1", "Select Bluetooth or WiFi Printer mode at the top"),
            Pair("2", "Connect paired BT printer OR configure WiFi printer IP"),
            Pair("3", "Go to Settings → Print → FFit BT → Toggle ON"),
            Pair("4", "In any app (Chrome, etc.) → Print → Select FFit BT")
        )
        val stepViews = listOf(
            binding.step1.root, binding.step2.root,
            binding.step3.root, binding.step4.root
        )
        steps.zip(stepViews).forEach { (data, view) ->
            view.findViewById<TextView>(R.id.tvStepNum).text = data.first
            view.findViewById<TextView>(R.id.tvStepText).text = data.second
        }
    }

    // ─── Helpers ─────────────────────────────────
    private fun snack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.card_bg))
            .setTextColor(getColor(R.color.text_primary))
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun loadPrintLogs() {
        val logs = dbHelper.getAllLogs()
        if (logs.isEmpty()) {
            binding.tvQueueEmpty.visibility = View.VISIBLE
            binding.rvPrintLogs.visibility = View.GONE
        } else {
            binding.tvQueueEmpty.visibility = View.GONE
            binding.rvPrintLogs.visibility = View.VISIBLE
            
            if (logAdapter == null) {
                logAdapter = PrintLogAdapter(logs) { log ->
                    cancelPrintJob(log)
                }
                binding.rvPrintLogs.layoutManager = LinearLayoutManager(this)
                binding.rvPrintLogs.adapter = logAdapter
            } else {
                logAdapter?.updateData(logs)
            }
        }
    }

    private fun cancelPrintJob(log: PrintLog) {
        snack("⏳ Attempting to cancel print job...")
        val success = FFitPrintService.cancelJob(this, log.systemJobId)
        if (success) {
            snack("✅ Print job successfully cancelled.")
        } else {
            snack("ℹ️ Cancel request sent for print job.")
        }
        loadPrintLogs()
    }
}
