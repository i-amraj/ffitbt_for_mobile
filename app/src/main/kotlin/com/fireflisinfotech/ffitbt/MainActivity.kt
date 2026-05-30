package com.fireflisinfotech.ffitbt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private val BT_PERM_CODE = 101

    // ─────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSteps()

        binding.btnScan.setOnClickListener { checkPermissionsAndScan() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnTestPrint.setOnClickListener { doTestPrint() }
        binding.btnOpenPrintSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
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
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
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
        BluetoothPrinter.disconnect()
        updateUI()
        toast("Disconnected")
    }

    // ─── Test Print ───────────────────────────────
    private fun doTestPrint() {
        if (!BluetoothPrinter.isConnected) { snack("❌ No printer connected"); return }
        binding.btnTestPrint.isEnabled = false
        Thread {
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
            esc.add(EscPos.FEED_3)
            esc.add(EscPos.CUT)

            val ok = BluetoothPrinter.send(esc.build())
            runOnUiThread {
                binding.btnTestPrint.isEnabled = true
                if (ok) snack("✅ Test page printed!")
                else snack("❌ Print failed — is printer ON?")
            }
        }.start()
    }

    // ─── UI Update ────────────────────────────────
    private fun updateUI() {
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
            binding.cardTestPrint.visibility = View.GONE
        }
    }

    // ─── Steps content ────────────────────────────
    private fun setupSteps() {
        val steps = listOf(
            Pair("1", "Pair your printer via Android Settings → Bluetooth"),
            Pair("2", "Open FFit BT → Scan → Connect your printer"),
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
}
