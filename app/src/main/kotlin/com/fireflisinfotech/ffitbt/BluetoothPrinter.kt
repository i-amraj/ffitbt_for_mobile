package com.fireflisinfotech.ffitbt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Manages Bluetooth Classic (SPP) connection to ESC/POS thermal printers.
 */
object BluetoothPrinter {
    private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    val isConnected: Boolean
        get() = try { socket?.isConnected == true } catch (_: Exception) { false }

    /**
     * Returns all paired devices as (name, mac, isPrinter) triples.
     * Shows all devices but flags which ones are likely printers.
     */
    fun getPairedDevicesWithType(context: Context): List<Triple<String, String, Boolean>> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        return adapter.bondedDevices.map { device ->
            Triple(
                device.name ?: "Unknown Device",
                device.address,
                isPrinter(device)
            )
        }.sortedByDescending { it.third } // printers first
    }

    /**
     * Also kept for backward compat with PrintService discovery.
     */
    fun getPairedDevices(context: Context): List<Pair<String, String>> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.map { Pair(it.name ?: "Unknown", it.address) }
    }

    /**
     * Determines if a BT device is likely a thermal printer using:
     * 1. BluetoothClass IMAGING major class (most accurate)
     * 2. Device name keyword matching (fallback for printers reporting wrong class)
     */
    fun isPrinter(device: BluetoothDevice): Boolean {
        val btClass = device.bluetoothClass

        // Check Bluetooth device class — IMAGING includes printers
        if (btClass != null &&
            btClass.majorDeviceClass == BluetoothClass.Device.Major.IMAGING) {
            return true
        }

        // Name-based fallback for printers that report wrong class
        val name = (device.name ?: "").lowercase()
        val printerKeywords = listOf(
            "printer", "print", "pos", "thermal", "receipt",
            "rpp", "hop", "xp-", "mtp", "zj-", "pt-",
            "bluetooth printer", "mini printer"
        )
        return printerKeywords.any { name.contains(it) }
    }

    /** Blocking connect — call from background thread */
    fun connect(mac: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return try {
            disconnect()
            adapter.cancelDiscovery()
            val device = adapter.getRemoteDevice(mac)
            val uuid = UUID.fromString(SPP_UUID)
            val s = device.createRfcommSocketToServiceRecord(uuid)
            s.connect()
            socket = s
            output = s.outputStream
            true
        } catch (e: IOException) {
            e.printStackTrace()
            disconnect()
            false
        } catch (e: SecurityException) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    /** Send raw bytes — call from background thread */
    fun send(bytes: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            out.write(bytes)
            out.flush()
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun disconnect() {
        try { output?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        output = null
        socket = null
    }
}
