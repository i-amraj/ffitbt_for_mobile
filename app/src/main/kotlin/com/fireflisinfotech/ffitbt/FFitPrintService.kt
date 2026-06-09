package com.fireflisinfotech.ffitbt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession

/**
 * FFitPrintService — Registers FFit BT as a system-level Android print service.
 *
 * KEY FIXES:
 * 1. Auto-reconnects using saved MAC — works even when app is closed.
 * 2. PrinterCapabilitiesInfo is set so print button is never disabled.
 * 3. Footer uses ESC/POS CP437 heart (♥) instead of Unicode emoji.
 *
 * The user connects once in the app → saved to prefs → PrintService reads
 * that MAC and reconnects on every print job automatically.
 */
class FFitPrintService : PrintService() {

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return FFitDiscoverySession(this)
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        android.util.Log.e("FFit", "onPrintJobQueued called!")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        printJob.start()

        // ── MUST READ DOCUMENT ON MAIN THREAD ──
        // Accessing printJob.document from a background thread throws IllegalAccessError!
        val pfd = printJob.document?.data
        if (pfd == null) {
            android.util.Log.e("FFit", "PDF data (pfd) is null on Main Thread!")
            printJob.fail("No document data received.")
            return
        }

        // Get localId on main thread to prevent "must be called from main thread" exception
        val localId = printJob.info?.printerId?.localId ?: ""

        Thread {
            try {
                android.util.Log.e("FFit", "Starting background thread for print job")
                val prefs = applicationContext.getSharedPreferences("ffitbt", Context.MODE_PRIVATE)
                val mode = if (localId == "wifi_printer") "wifi" else "bluetooth"

                if (mode == "wifi") {
                    val ip = PrinterPrefs.getSavedIp(prefs)
                    val port = PrinterPrefs.getSavedPort(prefs)
                    android.util.Log.e("FFit", "WiFi Mode: Connecting to $ip:$port...")
                    
                    if (!WifiPrinter.isConnected) {
                        val connected = WifiPrinter.connect(ip, port)
                        if (!connected) {
                            android.util.Log.e("FFit", "WifiPrinter.connect($ip, $port) failed!")
                            mainHandler.post {
                                printJob.fail(
                                    "Cannot connect to WiFi printer at $ip:$port. " +
                                    "Make sure the printer is ON and connected to the same network."
                                )
                            }
                            return@Thread
                        }
                    }
                } else {
                    // ── Auto-connect using target Bluetooth MAC ──
                    if (!BluetoothPrinter.isConnected) {
                        android.util.Log.e("FFit", "Printer not connected. Attempting auto-connect to $localId...")
                        val name = PrinterPrefs.getSavedName(prefs)

                        if (localId.isEmpty()) {
                            android.util.Log.e("FFit", "No Bluetooth MAC address received in printerId!")
                            mainHandler.post {
                                printJob.fail(
                                    "No printer configured. Open FFit BT app, " +
                                    "scan and connect your printer first."
                                )
                            }
                            return@Thread
                        }

                        val connected = BluetoothPrinter.connect(localId)
                        if (!connected) {
                            android.util.Log.e("FFit", "BluetoothPrinter.connect($localId) returned false!")
                            mainHandler.post {
                                printJob.fail(
                                    "Cannot connect to $name. " +
                                    "Make sure the printer is ON and in Bluetooth range."
                                )
                            }
                            return@Thread
                        }
                        android.util.Log.e("FFit", "Connected successfully!")
                    }
                }

                android.util.Log.e("FFit", "Copying PFD to temp file...")

                // PdfRenderer requires a seekable file descriptor. The Spooler's PFD might be a pipe.
                val tempPdf = java.io.File(cacheDir, "temp_print_job.pdf")
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    java.io.FileOutputStream(tempPdf).use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Open seekable fd
                val seekablePfd = android.os.ParcelFileDescriptor.open(
                    tempPdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )

                // ── Render and print each PDF page ──
                // 58mm thermal = 384px wide at 203 dpi
                val printWidthPx = 384
                val renderer = android.graphics.pdf.PdfRenderer(seekablePfd)
                val pageCount = renderer.pageCount

                for (pageIdx in 0 until pageCount) {
                    val page = renderer.openPage(pageIdx)
                    val scale  = printWidthPx.toFloat() / page.width
                    // Height is calculated from scale, capped at 12000 to prevent OOM
                    val renderH = (page.height * scale).toInt().coerceIn(1, 12000)

                    val bmp = Bitmap.createBitmap(
                        printWidthPx, renderH, Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(Color.WHITE)
                    page.render(
                        bmp, null, null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                    )
                    page.close()

                    val croppedBmp = cropWhitespace(bmp)

                    val esc = EscPos()
                    esc.add(EscPos.INIT)
                    esc.add(EscPos.ALIGN_CENTER)
                    esc.addRaster(bitmapToRaster(croppedBmp))
                    
                    bmp.recycle()
                    if (croppedBmp !== bmp) {
                        croppedBmp.recycle()
                    }

                    if (mode == "wifi") {
                        WifiPrinter.send(esc.build())
                    } else {
                        BluetoothPrinter.send(esc.build())
                    }
                }
                renderer.close()
                seekablePfd.close()
                tempPdf.delete()
                sendCompanyFooter(mode)
                if (mode == "wifi") {
                    WifiPrinter.disconnect()
                }
                // ── Print job complete ──
                android.util.Log.e("FFit", "Print job complete! Calling printJob.complete()")
                mainHandler.post { printJob.complete() }

            } catch (e: Throwable) {
                android.util.Log.e("FFit", "CRASH PREVENTED: ", e)
                e.printStackTrace()
                mainHandler.post { printJob.fail("Crash prevented: ${e.message}") }
            }
        }.start()
    }

    /**
     * Crops completely white rows from the top and bottom of the bitmap.
     * Prevents wasting thermal paper when Chrome centers a small bill on a large page.
     */
    private fun cropWhitespace(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var firstNonWhiteRow = -1
        var lastNonWhiteRow = -1

        for (y in 0 until height) {
            var isWhiteRow = true
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val alpha = (color shr 24) and 0xFF
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                
                // Content pixel is visible (alpha > 10) AND not white (r,g,b < 250)
                if (alpha > 10 && (r < 250 || g < 250 || b < 250)) {
                    isWhiteRow = false
                    break
                }
            }
            if (!isWhiteRow) {
                firstNonWhiteRow = y
                break
            }
        }

        if (firstNonWhiteRow < 0) {
            // Completely blank page, return a 1x1 dummy to avoid crash
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        for (y in height - 1 downTo firstNonWhiteRow) {
            var isWhiteRow = true
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val alpha = (color shr 24) and 0xFF
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                
                if (alpha > 10 && (r < 250 || g < 250 || b < 250)) {
                    isWhiteRow = false
                    break
                }
            }
            if (!isWhiteRow) {
                lastNonWhiteRow = y
                break
            }
        }

        // Leave a small margin (e.g., 10px) at top and bottom so text isn't cut too sharply
        val cropTop = (firstNonWhiteRow - 10).coerceAtLeast(0)
        val cropBottom = (lastNonWhiteRow + 20).coerceAtMost(height - 1)
        val croppedHeight = cropBottom - cropTop + 1

        return Bitmap.createBitmap(bitmap, 0, cropTop, width, croppedHeight)
    }
        /**
     * Stylish Company Footer.
     * Isko call karne par billing content ke niche horizontal line, bada company name 
     * aur cut space print hoga.
     */
    private fun sendCompanyFooter(mode: String) {
        val esc = EscPos()
        
        // 1. Text ko center align karein aur space chodein
        esc.add(EscPos.ALIGN_CENTER)
        esc.text("") // Ek khali line space ke liye
        esc.separator() // Horizontal Dotted/Dashed line (----)
        
        // 2. Text ko Bada aur Bold karne ki commands
     //   esc.add(byteArrayOf(0x1D, 0x21, 0x11)) // GS ! 17 (Double width + Double height)
        esc.add(EscPos.BOLD_ON)                // BOLD_ON command
        
        // Yahan apni company ka name likhein
        esc.text("Powered by- FFIT.IO") 
        
        // 3. Text size ko wapas normal karein aur Bold band karein
      //  esc.add(byteArrayOf(0x1D, 0x21, 0x00)) // GS ! 0 (Normal size)
        esc.add(EscPos.BOLD_OFF)
        
       
        
        // 4. Paper feed karein aur cut command bhein
        esc.add(byteArrayOf(0x1B, 0x64, 0x06)) // Print ke baad extra 6 lines feed karega taaki footer cut na ho
        esc.add(EscPos.CUT)    // Paper cutting command (agar printer support karta ho)
        
        if (mode == "wifi") {
            WifiPrinter.send(esc.build())
        } else {
            BluetoothPrinter.send(esc.build())
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }
}

/**
 * FFitDiscoverySession — Exposes only the saved printer to Android's PrintManager.
 *
 * CRITICAL: PrinterCapabilitiesInfo MUST be set or print button stays DISABLED.
 * We only show the printer saved in SharedPreferences (not all BT devices).
 */
class FFitDiscoverySession(private val service: PrintService) : PrinterDiscoverySession() {

    private fun buildPrinterInfo(id: PrinterId, name: String, connected: Boolean): PrinterInfo {
        // 58mm thermal paper: 58mm = ~2283 mils. Height = 297mm (same as A4) = ~11692 mils.
        val size58mm = PrintAttributes.MediaSize("receipt_58", "58mm Receipt", 2283, 11692)
        // 80mm thermal paper: 80mm = ~3150 mils. Height = 297mm (same as A4) = ~11692 mils.
        val size80mm = PrintAttributes.MediaSize("receipt_80", "80mm Receipt", 3150, 11692)

        val capabilities = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(size58mm, true) // DEFAULT IS NOW 58MM WITH STANDARD HEIGHT
            .addMediaSize(size80mm, false)
            .addResolution(
                PrintAttributes.Resolution("res_203", "203 DPI", 203, 203), true
            )
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_MONOCHROME
            )
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        return PrinterInfo.Builder(
            id,
            name.ifEmpty { "FFit BT Printer" },
            if (connected) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE
        )
            .setCapabilities(capabilities)
            .build()
    }

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        val prefs = service.applicationContext
            .getSharedPreferences("ffitbt", Context.MODE_PRIVATE)
        
        val printers = mutableListOf<PrinterInfo>()

        // 1. Add Bluetooth printer if configured
        val mac = PrinterPrefs.getSavedMac(prefs)
        val name = PrinterPrefs.getSavedName(prefs)
        if (mac.isNotEmpty()) {
            val id = service.generatePrinterId(mac)
            printers.add(buildPrinterInfo(id, name.ifEmpty { "FFit BT Printer" }, true))
        }

        // 2. Add WiFi printer if configured
        val wifiIp = PrinterPrefs.getSavedIp(prefs)
        val wifiSsid = PrinterPrefs.getSavedSsid(prefs)
        if (wifiIp.isNotEmpty()) {
            val id = service.generatePrinterId("wifi_printer")
            val displayName = if (wifiSsid.isNotEmpty()) wifiSsid else "WiFi Printer ($wifiIp)"
            printers.add(buildPrinterInfo(id, displayName, true))
        }

        if (printers.isNotEmpty()) {
            addPrinters(printers)
        }
    }

    override fun onStopPrinterDiscovery() {}

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        val prefs = service.applicationContext
            .getSharedPreferences("ffitbt", Context.MODE_PRIVATE)
        val localId = printerId.localId
        
        if (localId == "wifi_printer") {
            val wifiIp = PrinterPrefs.getSavedIp(prefs)
            val wifiSsid = PrinterPrefs.getSavedSsid(prefs)
            val displayName = if (wifiSsid.isNotEmpty()) wifiSsid else "WiFi Printer ($wifiIp)"
            addPrinters(listOf(buildPrinterInfo(printerId, displayName, true)))
        } else {
            // Assume Bluetooth MAC
            val name = PrinterPrefs.getSavedName(prefs)
            addPrinters(listOf(buildPrinterInfo(printerId, name.ifEmpty { "FFit BT Printer" }, true)))
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}
    override fun onDestroy() {}
}
