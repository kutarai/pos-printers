package zw.co.unipay.printers

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fiscal receipt: the tax authority's lines printed whole, its QR code printed as a picture
 * inside the fiscal part, and the till's own item list left out when the authority's text
 * already has one.
 */
class FiscalReceiptTest {

    private val content = ReceiptContent(
        merchantName = "ACME", receiptNumber = "R-1", dateTime = "08 Nov 2026 10:00", terminalId = "T1",
        lines = listOf(ReceiptLine("Bread", 2.0, 1.25, 2.50)),
        subtotal = 2.17, tax = 0.33, total = 2.50, currency = "USD",
        tendered = 5.0, change = 2.50, paymentMethod = "CASH"
    )

    @Test
    fun `a receipt without a fiscal part has no QR code and ends with the footer`() {
        val doc = ReceiptLayout(32).document(content)
        assertNull(doc.qrCode)
        assertTrue(doc.lines.last().contains("Thank you"))
    }

    @Test
    fun `the fiscal lines follow the sale and the QR code sits between them and what comes after`() {
        val doc = ReceiptLayout(32).document(content.copy(fiscal = FiscalSection(
            lines = listOf("FISCAL TAX INVOICE", "Receipt: 1/41"),
            qrCode = "https://fdmstest.zimra.co.zw/0000000321",
            linesAfterQr = listOf("Verification code:", "0123-4567-89AB-CDEF")
        )))

        assertTrue(doc.lines.any { it.contains("Bread") })
        assertEquals("Receipt: 1/41", doc.lines.last())
        assertEquals("https://fdmstest.zimra.co.zw/0000000321", doc.qrCode)
        assertEquals("Verification code:", doc.linesAfterQr.first())
    }

    @Test
    fun `when the authority's text lists the items the till's own list is left out`() {
        val doc = ReceiptLayout(32).document(content.copy(fiscal = FiscalSection(
            lines = listOf("===== FISCAL INVOICE =====", "Bread (A) 2.50"), qrCode = "u", replacesItems = true
        )))
        assertFalse(doc.lines.any { it.startsWith("Bread ") && it.endsWith("2.50") && !it.contains("(A)") })
        assertFalse(doc.lines.any { it.startsWith("Subtotal") })
        // What was handed over and the change still show: the authority's text has no change.
        assertTrue(doc.lines.any { it.startsWith("Change") })
    }

    @Test
    fun `a fiscal line wider than the paper is wrapped, never cut`() {
        val layout = ReceiptLayout(32)
        val line = "SDC Invoice No: 7AF234D9-E377B30A-150493 extra words here"
        val wrapped = layout.wrap(line)
        assertTrue(wrapped.all { it.length <= 32 })
        assertEquals(line.replace(" ", ""), wrapped.joinToString("").replace(" ", ""))
    }

    /** Every GS v 0 band in the job, joined into one picture with a paper margin round it. */
    private fun printedQr(bytes: ByteArray): Pair<BinaryBitmap, Pair<Int, Int>> {
        val rows = ArrayList<BooleanArray>()
        var i = 0
        var width = 0
        while (i + 8 <= bytes.size) {
            if (bytes[i] == 0x1D.toByte() && bytes[i + 1] == 'v'.code.toByte() && bytes[i + 2] == '0'.code.toByte()) {
                val widthBytes = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8)
                val height = (bytes[i + 6].toInt() and 0xFF) or ((bytes[i + 7].toInt() and 0xFF) shl 8)
                width = widthBytes * 8
                i += 8
                repeat(height) {
                    rows += BooleanArray(width) { x -> bytes[i + x / 8].toInt() and (0x80 shr (x % 8)) != 0 }
                    i += widthBytes
                }
            } else i++
        }
        val margin = 40
        val pixels = IntArray((width + 2 * margin) * (rows.size + 2 * margin)) { 0xFFFFFF }
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) pixels[(y + margin) * (width + 2 * margin) + x + margin] = 0
            }
        }
        val source = RGBLuminanceSource(width + 2 * margin, rows.size + 2 * margin, pixels)
        return BinaryBitmap(HybridBinarizer(source)) to (width to rows.size)
    }

    private fun decode(bitmap: BinaryBitmap): String =
        QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text

    /** As long as the link the TaxCore sandbox really returned. */
    private val taxCoreLink = "https://sandbox.taxcore.online/v/?vl=" +
        (1..812).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789%"[it * 7919 % 63] }.joinToString("")

    @Test
    fun `a full length TaxCore link prints as a code that reads back whole and uses the paper`() {
        assertEquals(849, taxCoreLink.length)
        val (bitmap, size) = printedQr(EscPos.document(ReceiptDocument(listOf("before"), taxCoreLink, listOf("after"))))
        assertEquals(taxCoreLink, decode(bitmap))
        assertEquals(384, size.first)
        assertEquals(384, size.second)
    }

    @Test
    fun `on 80mm paper the same code is larger and still reads back`() {
        val (bitmap, size) = printedQr(EscPos.document(ReceiptDocument(emptyList(), taxCoreLink), PrinterSettings.WIDE_ROLL))
        assertEquals(taxCoreLink, decode(bitmap))
        assertEquals(576, size.first)
        assertEquals(576, size.second)
    }

    @Test
    fun `a short link still meets the 42mm minimum and reads back`() {
        val link = "https://fdmstest.zimra.co.zw/0000000321"
        val (bitmap, size) = printedQr(EscPos.document(ReceiptDocument(emptyList(), link)))
        assertEquals(link, decode(bitmap))
        assertEquals(336, size.second)
    }

    @Test
    fun `the code sits between the lines before and after it`() {
        val text = String(EscPos.document(ReceiptDocument(listOf("before"), "https://x.test/1", listOf("after"))), Charsets.ISO_8859_1)
        val code = text.indexOf("\u001Dv0")
        assertTrue(text.indexOf("before") in 0 until code)
        assertTrue(code < text.lastIndexOf("after"))
    }
}
