package zw.co.unipay.printers

import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Turns finished lines into the bytes an ESC/POS printer expects.
 *
 * These printers never answer back: whatever is written to the socket is accepted, and a wrong
 * byte produces a receipt that is subtly wrong — no cut, or the rest of the roll in double width,
 * or the escape sequence printed as characters — while the till reports success. Kept apart from
 * the Bluetooth socket so the bytes can be asserted without a printer on the desk.
 */
object EscPos {

    private const val ESC = 0x1B.toByte()
    private const val GS = 0x1D.toByte()

    /** How far the paper is fed before cutting, so the cut misses the last printed line. */
    private const val FEED_LINES = 3

    private const val ESC_INT = 0x1B

    /** The ERS requires a QR code of at least 42 mm square: 336 dots. */
    private const val QR_MIN_DOTS = 336

    /** How coarse a code may get on wide paper before it is only wasting receipt. */
    private const val QR_DOTS_PER_MODULE = 6

    /** Blank feed above and below the code, about 3 mm. */
    private const val QR_QUIET_DOTS = 24

    private const val RASTER_BAND_ROWS = 64

    fun document(lines: List<String>): ByteArray = document(ReceiptDocument(lines))

    /**
     * A receipt with, optionally, a QR code between two runs of lines - where a fiscal receipt
     * needs it, inside the fiscal part rather than after it. [paperColumns] is the roll in use,
     * as [PrinterSettings.paperWidth] gives it, and sets how wide the code can be.
     */
    fun document(document: ReceiptDocument, paperColumns: Int = PrinterSettings.NARROW_ROLL): ByteArray {
        val out = ArrayList<Byte>(512)

        // ESC @ — reset. A printer left inverted or in double width by the previous job would
        // otherwise print this one the same way.
        out += ESC
        out += '@'.code.toByte()

        for (line in document.lines) {
            out += ascii(line)
            out += '\n'.code.toByte()
        }

        document.qrCode?.takeIf { it.isNotEmpty() }?.let { qr(out, it, printableDots(paperColumns)) }

        for (line in document.linesAfterQr) {
            out += ascii(line)
            out += '\n'.code.toByte()
        }

        // ESC d n — feed clear of the head, then GS V 1 — partial cut. Without the feed the cut
        // lands across the last line printed, which is usually the total.
        out += ESC
        out += 'd'.code.toByte()
        out += FEED_LINES.toByte()

        out += GS
        out += 'V'.code.toByte()
        out += 1.toByte()

        return out.toByteArray()
    }

    /** Dots an 8 dot/mm head prints across: 48 mm on a 58 mm roll, 72 mm on an 80 mm roll. */
    private fun printableDots(paperColumns: Int): Int =
        if (paperColumns >= PrinterSettings.WIDE_ROLL) 576 else 384

    /**
     * The QR code as a picture, centred on the full width of the head.
     *
     * Not left to the printer's own QR command (GS ( k): a TaxCore verification link runs to some
     * 850 characters, which that command could only draw in squares three dots wide, too fine to
     * scan once printed on thermal paper. Here error correction L keeps the code as small as it
     * can be, and the picture then uses all the width there is. The paper beyond the head is the
     * quiet zone at the sides; a short feed makes it above and below.
     */
    private fun qr(out: ArrayList<Byte>, payload: String, printable: Int) {
        val matrix = try {
            Encoder.encode(payload, ErrorCorrectionLevel.L).matrix
        } catch (e: WriterException) {
            return  // Longer than any QR code holds. The receipt still has to come out.
        }
        put(out, ESC_INT, 'J'.code, QR_QUIET_DOTS)
        raster(out, qrRows(matrix, printable), printable / 8)
        put(out, ESC_INT, 'J'.code, QR_QUIET_DOTS)
    }

    /**
     * The code's rows as packed bits, one byte array per dot row, each [printable] / 8 bytes wide.
     * A module is a whole number of dots only when that happens to fit; otherwise modules differ
     * by at most a dot, which readers follow from the timing pattern.
     */
    private fun qrRows(matrix: ByteMatrix, printable: Int): List<ByteArray> {
        val modules = matrix.width
        val side = minOf(printable, maxOf(QR_MIN_DOTS, modules * QR_DOTS_PER_MODULE))
        val left = (printable - side) / 2
        return (0 until side).map { y ->
            val row = ByteArray(printable / 8)
            val moduleY = y * modules / side
            for (x in 0 until side) {
                if (matrix.get(x * modules / side, moduleY).toInt() == 1) {
                    val dot = left + x
                    row[dot / 8] = (row[dot / 8].toInt() or (0x80 shr (dot % 8))).toByte()
                }
            }
            row
        }
    }

    /** GS v 0, in bands, because a whole code in one command overruns some printers' buffers. */
    private fun raster(out: ArrayList<Byte>, rows: List<ByteArray>, widthBytes: Int) {
        for (band in rows.chunked(RASTER_BAND_ROWS)) {
            put(out, 0x1D, 'v'.code, '0'.code, 0, widthBytes and 0xFF, widthBytes shr 8, band.size and 0xFF, band.size shr 8)
            band.forEach { out += it.toList() }
        }
    }

    private fun put(out: ArrayList<Byte>, vararg values: Int) {
        for (v in values) out += v.toByte()
    }

    /**
     * What a thermal printer's built-in code page can actually render.
     *
     * Item names are typed at head office on a full keyboard, so em dashes and curly quotes
     * arrive routinely. Sent raw they print as a stray glyph or as nothing; the line still has
     * to read at the counter.
     */
    private fun ascii(text: String): List<Byte> = text
        .replace('—', '-')
        .replace('–', '-')
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('…', '.')
        .map { char -> if (char.code in 32..126) char.code.toByte() else '?'.code.toByte() }
}
