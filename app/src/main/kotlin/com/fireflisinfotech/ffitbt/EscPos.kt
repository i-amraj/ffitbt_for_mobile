package com.fireflisinfotech.ffitbt

/**
 * ESC/POS command builder for 58mm thermal printers.
 * Build receipt bytes then call .build() to get the final ByteArray.
 */
class EscPos {
    private val buf = mutableListOf<Int>()

    companion object {
        // Commands
        val INIT         = byteArrayOf(0x1B, 0x40)
        val ALIGN_LEFT   = byteArrayOf(0x1B, 0x61, 0x00)
        val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
        val BOLD_ON      = byteArrayOf(0x1B, 0x45, 0x01)
        val BOLD_OFF     = byteArrayOf(0x1B, 0x45, 0x00)
        val DBLH_ON      = byteArrayOf(0x1D, 0x21, 0x01)
        val DBLH_OFF     = byteArrayOf(0x1D, 0x21, 0x00)
        val FEED_3       = byteArrayOf(0x1B, 0x64, 0x03)
        val CUT          = byteArrayOf(0x1D, 0x56, 0x00)
        const val LF     = 0x0A
        const val WIDTH  = 32   // 58mm = 32 chars per line
    }

    fun add(bytes: ByteArray) = apply { bytes.forEach { buf.add(it.toInt() and 0xFF) } }

    fun text(s: String) = apply {
        s.forEach { c -> buf.add(if (c.code < 256) c.code else 63) }
        buf.add(LF)
    }

    fun separator() = text("-".repeat(WIDTH))

    fun twoCol(left: String, right: String) = apply {
        val gap = WIDTH - left.length - right.length
        text(left + " ".repeat(if (gap > 0) gap else 1) + right)
    }

    fun build(): ByteArray = buf.map { it.toByte() }.toByteArray()

    // ── Raster image (GS v 0) for logo or text-as-image ──
    fun addRaster(rasterBytes: ByteArray) = apply {
        rasterBytes.forEach { buf.add(it.toInt() and 0xFF) }
    }

    /** Add raw ASCII text WITHOUT a trailing newline (used for mixed-byte lines) */
    fun addRawText(s: String) = apply {
        s.forEach { c -> buf.add(if (c.code < 256) c.code else 63) }
    }

    /** Add a single raw byte (e.g. 0x03 = ♥ in CP437 code page) */
    fun addByte(b: Int) = apply { buf.add(b and 0xFF) }
}

/**
 * Converts a grayscale Android Bitmap into ESC/POS GS v 0 raster bytes.
 */
fun bitmapToRaster(bitmap: android.graphics.Bitmap): ByteArray {
    val w = bitmap.width
    val h = bitmap.height
    val widthBytes = (w + 7) / 8
    val result = mutableListOf<Byte>()

    // GS v 0 header
    result.addAll(listOf(0x1D, 0x76, 0x30, 0x00).map { it.toByte() })
    result.add((widthBytes and 0xFF).toByte())
    result.add(((widthBytes shr 8) and 0xFF).toByte())
    result.add((h and 0xFF).toByte())
    result.add(((h shr 8) and 0xFF).toByte())

    for (y in 0 until h) {
        for (bx in 0 until widthBytes) {
            var byte = 0
            for (bit in 0 until 8) {
                val x = bx * 8 + bit
                if (x < w) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                    if (luminance < 128) byte = byte or (0x80 shr bit)
                }
            }
            result.add(byte.toByte())
        }
    }
    return result.toByteArray()
}
