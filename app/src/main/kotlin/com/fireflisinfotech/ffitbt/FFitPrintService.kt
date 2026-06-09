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
 * KEY FEATURES:
 * 1. Auto-reconnects using saved MAC/IP — works even when app is closed.
 * 2. PrinterCapabilitiesInfo is set so print button is never disabled.
 * 3. Sequential Queue Processing via SingleThreadExecutor.
 * 4. Job Cancellation & Reprinting support directly from SQLite log history.
 */
class FFitPrintService : PrintService() {

    companion object {
        val activeJobs = java.util.Collections.synchronizedList(mutableListOf<PrintJob>())
        val cancelledRestartJobs = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val printExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        fun cancelJob(context: android.content.Context, jobId: String): Boolean {
            // 1. Try to cancel system print job
            synchronized(activeJobs) {
                val iterator = activeJobs.iterator()
                while (iterator.hasNext()) {
                    val job = iterator.next()
                    val idStr = try {
                        job.info?.id?.toString() ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    if (idStr == jobId) {
                        try {
                            job.cancel()
                            val db = PrintDatabaseHelper(context)
                            db.updateStatus(jobId, "cancelled")
                            iterator.remove()
                            context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                            return true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
            
            // 2. If it's a restarted/retry job, add to cancelled restarted jobs set
            cancelledRestartJobs.add(jobId)
            try {
                val db = PrintDatabaseHelper(context)
                db.updateStatus(jobId, "cancelled")
                context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        fun restartPrintJob(context: android.content.Context, jobId: String): Boolean {
            val db = PrintDatabaseHelper(context)
            val logs = db.getAllLogs()
            val targetLog = logs.find { it.systemJobId == jobId } ?: return false

            val cacheFile = java.io.File(context.cacheDir, "job_${jobId}.pdf")
            if (!cacheFile.exists()) {
                android.util.Log.e("FFit", "Cache file job_${jobId}.pdf does not exist!")
                return false
            }

            // Remove from cancelled restart set if it was there before
            cancelledRestartJobs.remove(jobId)

            // Mark status as queued in DB
            db.updateStatus(jobId, "queued")
            context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))

            printExecutor.submit {
                val prefs = context.getSharedPreferences("ffitbt", Context.MODE_PRIVATE)
                val mode = targetLog.printerType
                val target = targetLog.printerTarget

                try {
                    if (cancelledRestartJobs.contains(jobId)) {
                        db.updateStatus(jobId, "cancelled")
                        cancelledRestartJobs.remove(jobId)
                        context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                        return@submit
                    }

                    db.updateStatus(jobId, "printing")
                    context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))

                    // Connect to printer
                    if (mode == "wifi") {
                        val ip = PrinterPrefs.getSavedIp(prefs)
                        val port = PrinterPrefs.getSavedPort(prefs)
                        if (!WifiPrinter.isConnected) {
                            val connected = WifiPrinter.connect(ip, port)
                            if (!connected) {
                                db.updateStatus(jobId, "failed", "Cannot connect to WiFi printer at $ip:$port.")
                                context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                                return@submit
                            }
                        }
                    } else {
                        if (!BluetoothPrinter.isConnected) {
                            if (target.isEmpty()) {
                                db.updateStatus(jobId, "failed", "No Bluetooth MAC address configured.")
                                context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                                return@submit
                            }
                            val name = PrinterPrefs.getSavedName(prefs)
                            val connected = BluetoothPrinter.connect(target)
                            if (!connected) {
                                db.updateStatus(jobId, "failed", "Cannot connect to $name.")
                                context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                                return@submit
                            }
                        }
                    }

                    if (cancelledRestartJobs.contains(jobId)) {
                        db.updateStatus(jobId, "cancelled")
                        cancelledRestartJobs.remove(jobId)
                        context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                        return@submit
                    }

                    // Render and print PDF
                    val seekablePfd = android.os.ParcelFileDescriptor.open(
                        cacheFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    val printWidthPx = 384
                    val renderer = android.graphics.pdf.PdfRenderer(seekablePfd)
                    val pageCount = renderer.pageCount

                    for (pageIdx in 0 until pageCount) {
                        if (cancelledRestartJobs.contains(jobId)) {
                            break
                        }
                        val page = renderer.openPage(pageIdx)
                        val scale = printWidthPx.toFloat() / page.width
                        val renderH = (page.height * scale).toInt().coerceIn(1, 12000)

                        val bmp = Bitmap.createBitmap(printWidthPx, renderH, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        page.close()

                        val croppedBmp = cropWhitespaceStatic(bmp)
                        val esc = EscPos()
                        esc.add(EscPos.INIT)
                        esc.add(EscPos.ALIGN_CENTER)
                        esc.addRaster(bitmapToRaster(croppedBmp))

                        bmp.recycle()
                        if (croppedBmp !== bmp) croppedBmp.recycle()

                        if (mode == "wifi") {
                            WifiPrinter.send(esc.build())
                        } else {
                            BluetoothPrinter.send(esc.build())
                        }
                    }
                    renderer.close()
                    seekablePfd.close()

                    if (!cancelledRestartJobs.contains(jobId)) {
                        sendCompanyFooterStatic(mode)
                        if (mode == "wifi") {
                            WifiPrinter.disconnect()
                        }
                        db.updateStatus(jobId, "completed")
                    } else {
                        db.updateStatus(jobId, "cancelled")
                        cancelledRestartJobs.remove(jobId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    db.updateStatus(jobId, "failed", e.message ?: "Reprint failed")
                } finally {
                    context.sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                }
            }
            return true
        }
    }

    private fun isJobCancelledMain(printJob: PrintJob): Boolean {
        var cancelled = false
        val latch = java.util.concurrent.CountDownLatch(1)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                cancelled = printJob.isCancelled
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cancelled
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return FFitDiscoverySession(this)
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        android.util.Log.e("FFit", "onPrintJobQueued called!")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        val pfd = printJob.document?.data
        if (pfd == null) {
            android.util.Log.e("FFit", "PDF data (pfd) is null on Main Thread!")
            printJob.fail("No document data received.")
            return
        }

        val localId = printJob.info?.printerId?.localId ?: ""
        val jobId = printJob.info?.id?.toString() ?: System.currentTimeMillis().toString()
        val title = printJob.info?.label ?: "Print Job"
        val prefs = applicationContext.getSharedPreferences("ffitbt", Context.MODE_PRIVATE)
        val mode = if (localId == "wifi_printer") "wifi" else "bluetooth"
        val target = if (mode == "wifi") PrinterPrefs.getSavedIp(prefs) else localId

        // Log the queued job in db
        val db = PrintDatabaseHelper(applicationContext)
        db.insertLog(jobId, title, mode, target, "queued")
        activeJobs.add(printJob)

        // Broadcast queue update
        sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))

        printExecutor.submit {
            try {
                if (isJobCancelledMain(printJob)) {
                    activeJobs.remove(printJob)
                    db.updateStatus(jobId, "cancelled")
                    sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                    return@submit
                }

                // ── COPY Spooler PFD content to cacheDir/job_${jobId}.pdf ──
                val cacheFile = java.io.File(cacheDir, "job_${jobId}.pdf")
                try {
                    java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                        java.io.FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    db.updateStatus(jobId, "failed", "Could not cache PDF data: ${e.message}")
                    mainHandler.post { printJob.fail("Could not cache PDF data: ${e.message}") }
                    activeJobs.remove(printJob)
                    sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                    return@submit
                }

                db.updateStatus(jobId, "printing")
                sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))

                // Start print job on main thread
                var startOk = false
                val startLatch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    try {
                        startOk = printJob.start()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        startLatch.countDown()
                    }
                }
                startLatch.await()

                if (!startOk && !isJobCancelledMain(printJob)) {
                    db.updateStatus(jobId, "failed", "Could not start print job.")
                    activeJobs.remove(printJob)
                    sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                    return@submit
                }

                android.util.Log.e("FFit", "Starting background print logic")
                if (mode == "wifi") {
                    val ip = PrinterPrefs.getSavedIp(prefs)
                    val port = PrinterPrefs.getSavedPort(prefs)
                    if (!WifiPrinter.isConnected) {
                        val connected = WifiPrinter.connect(ip, port)
                        if (!connected) {
                            db.updateStatus(jobId, "failed", "Cannot connect to WiFi printer at $ip:$port.")
                            mainHandler.post { printJob.fail("Cannot connect to WiFi printer at $ip:$port.") }
                            activeJobs.remove(printJob)
                            sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                            return@submit
                        }
                    }
                } else {
                    if (!BluetoothPrinter.isConnected) {
                        if (localId.isEmpty()) {
                            db.updateStatus(jobId, "failed", "No Bluetooth MAC address configured.")
                            mainHandler.post { printJob.fail("No printer configured.") }
                            activeJobs.remove(printJob)
                            sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                            return@submit
                        }
                        val name = PrinterPrefs.getSavedName(prefs)
                        val connected = BluetoothPrinter.connect(localId)
                        if (!connected) {
                            db.updateStatus(jobId, "failed", "Cannot connect to $name.")
                            mainHandler.post { printJob.fail("Cannot connect to $name.") }
                            activeJobs.remove(printJob)
                            sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                            return@submit
                        }
                    }
                }

                if (isJobCancelledMain(printJob)) {
                    db.updateStatus(jobId, "cancelled")
                    mainHandler.post { printJob.cancel() }
                    activeJobs.remove(printJob)
                    sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
                    return@submit
                }

                // Render PDF from cacheFile
                val seekablePfd = android.os.ParcelFileDescriptor.open(
                    cacheFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )

                val printWidthPx = 384
                val renderer = android.graphics.pdf.PdfRenderer(seekablePfd)
                val pageCount = renderer.pageCount

                for (pageIdx in 0 until pageCount) {
                    if (isJobCancelledMain(printJob)) {
                        break
                    }
                    val page = renderer.openPage(pageIdx)
                    val scale  = printWidthPx.toFloat() / page.width
                    val renderH = (page.height * scale).toInt().coerceIn(1, 12000)

                    val bmp = Bitmap.createBitmap(printWidthPx, renderH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val croppedBmp = cropWhitespaceStatic(bmp)
                    val esc = EscPos()
                    esc.add(EscPos.INIT)
                    esc.add(EscPos.ALIGN_CENTER)
                    esc.addRaster(bitmapToRaster(croppedBmp))

                    bmp.recycle()
                    if (croppedBmp !== bmp) croppedBmp.recycle()

                    if (mode == "wifi") {
                        WifiPrinter.send(esc.build())
                    } else {
                        BluetoothPrinter.send(esc.build())
                    }
                }
                renderer.close()
                seekablePfd.close()

                if (!isJobCancelledMain(printJob)) {
                    sendCompanyFooterStatic(mode)
                    if (mode == "wifi") {
                        WifiPrinter.disconnect()
                    }
                    db.updateStatus(jobId, "completed")
                    mainHandler.post { printJob.complete() }
                } else {
                    db.updateStatus(jobId, "cancelled")
                    mainHandler.post { printJob.cancel() }
                }

            } catch (e: Throwable) {
                e.printStackTrace()
                db.updateStatus(jobId, "failed", e.message ?: "Unknown error")
                mainHandler.post { printJob.fail("Crash prevented: ${e.message}") }
            } finally {
                activeJobs.remove(printJob)
                sendBroadcast(android.content.Intent("com.fireflisinfotech.ffitbt.UPDATE_QUEUE"))
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }
}

/**
 * Crops completely white rows from the top and bottom of the bitmap.
 * Prevents wasting thermal paper when Chrome centers a small bill on a large page.
 */
fun cropWhitespaceStatic(bitmap: Bitmap): Bitmap {
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
 */
fun sendCompanyFooterStatic(mode: String) {
    val esc = EscPos()
    
    // 1. Text ko center align karein aur space chodein
    esc.add(EscPos.ALIGN_CENTER)
    esc.text("") // Ek khali line space ke liye
    esc.separator() // Horizontal Dotted/Dashed line (----)
    
    // 2. Text ko Bada aur Bold karne ki commands
    esc.add(EscPos.BOLD_ON)                // BOLD_ON command
    
    // Yahan apni company ka name likhein
    esc.text("Powered by- FFIT.IO") 
    
    // 3. Text size ko wapas normal karein aur Bold band karein
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

/**
 * FFitDiscoverySession — Exposes only the saved printer to Android's PrintManager.
 */
class FFitDiscoverySession(private val service: PrintService) : PrinterDiscoverySession() {

    private fun buildPrinterInfo(id: PrinterId, name: String, connected: Boolean): PrinterInfo {
        val size58mm = PrintAttributes.MediaSize("receipt_58", "58mm Receipt", 2283, 11692)
        val size80mm = PrintAttributes.MediaSize("receipt_80", "80mm Receipt", 3150, 11692)

        val capabilities = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(size58mm, true)
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

        val mac = PrinterPrefs.getSavedMac(prefs)
        val name = PrinterPrefs.getSavedName(prefs)
        if (mac.isNotEmpty()) {
            val id = service.generatePrinterId(mac)
            printers.add(buildPrinterInfo(id, name.ifEmpty { "FFit BT Printer" }, true))
        }

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
            val name = PrinterPrefs.getSavedName(prefs)
            addPrinters(listOf(buildPrinterInfo(printerId, name.ifEmpty { "FFit BT Printer" }, true)))
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}
    override fun onDestroy() {}
}
