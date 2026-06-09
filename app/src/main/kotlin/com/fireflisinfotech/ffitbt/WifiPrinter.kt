package com.fireflisinfotech.ffitbt

import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages raw TCP socket connection to ESC/POS thermal printers over WiFi/Ethernet.
 * Default AppSocket/JetDirect port is 9100.
 */
object WifiPrinter {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var currentIp: String = ""
    private var currentPort: Int = 9100

    val isConnected: Boolean
        get() = try { socket != null && socket!!.isConnected && !socket!!.isClosed } catch (_: Exception) { false }

    /**
     * Connects to the WiFi printer.
     * Note: Call from background thread to avoid NetworkOnMainThreadException.
     */
    fun connect(ip: String, port: Int): Boolean {
        return try {
            disconnect()
            val s = Socket()
            // 3 seconds connection timeout
            s.connect(InetSocketAddress(ip, port), 3000)
            socket = s
            output = s.getOutputStream()
            currentIp = ip
            currentPort = port
            true
        } catch (e: Exception) {
            e.printStackTrace()
            disconnect()
            false
        }
    }

    /**
     * Sends bytes to the printer.
     * Note: Call from background thread.
     */
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
