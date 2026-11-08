package zw.co.unipay.printers

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

    fun document(lines: List<String>): ByteArray = document(ReceiptDocument(lines))

    /**
     * A receipt with, optionally, a QR code between two runs of lines - where a fiscal receipt
     * needs it, inside the fiscal part rather than after it.
     */
    fun document(document: ReceiptDocument): ByteArray {
        val out = ArrayList<Byte>(512)

        // ESC @ — reset. A printer left inverted or in double width by the previous job would
        // otherwise print this one the same way.
        out += ESC
        out += '@'.code.toByte()

        for (line in document.lines) {
            out += ascii(line)
            out += '\n'.code.toByte()
        }

        document.qrCode?.takeIf { it.isNotEmpty() }?.let { qr(out, it) }

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

    /**
     * GS ( k — the printer draws the QR code itself, centred. Model 2, error correction M, and
     * modules sized so the code comes out about 42 mm square on an 8 dot/mm head: TaxCore
     * jurisdictions require at least that, and it scans easily anywhere.
     */
    private fun qr(out: ArrayList<Byte>, payload: String) {
        val data = payload.toByteArray(Charsets.ISO_8859_1)
        val esc = 0x1B
        val gs = 0x1D
        val k = 'k'.code
        val paren = '('.code
        // ESC a 1 — centre.
        put(out, esc, 'a'.code, 1)
        // Function 165: model 2.
        put(out, gs, paren, k, 4, 0, 49, 65, 50, 0)
        // Function 167: module size in dots.
        put(out, gs, paren, k, 3, 0, 49, 67, qrModuleSize(data.size))
        // Function 169: error correction level M.
        put(out, gs, paren, k, 3, 0, 49, 69, 49)
        // Function 180: store the data.
        val length = data.size + 3
        put(out, gs, paren, k, length and 0xFF, length shr 8, 49, 80, 48)
        out += data.toList()
        // Function 181: print it.
        put(out, gs, paren, k, 3, 0, 49, 81, 48)
        put(out, '\n'.code)
        // ESC a 0 — back to the left.
        put(out, esc, 'a'.code, 0)
    }

    private fun put(out: ArrayList<Byte>, vararg values: Int) {
        for (v in values) out += v.toByte()
    }

    /** Bytes a QR version holds at error correction M, versions 1 to 20. */
    private val QR_CAPACITY_M = intArrayOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213, 251, 287, 331, 362, 412, 450, 504, 560, 624, 666)

    /** Dots per module for about 340 dots (42 mm) across, kept between 3 and 8. */
    internal fun qrModuleSize(bytes: Int): Int {
        val version = QR_CAPACITY_M.indexOfFirst { bytes <= it }.let { if (it < 0) 20 else it + 1 }
        val modules = 17 + 4 * version + 8   // with its quiet zone
        return (340 / modules).coerceIn(3, 8)
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
