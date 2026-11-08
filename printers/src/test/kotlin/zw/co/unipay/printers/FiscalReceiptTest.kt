package zw.co.unipay.printers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fiscal receipt: the tax authority's lines printed whole, its QR code drawn by the printer
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

    @Test
    fun `the printer is told to draw the QR code with the payload inside it`() {
        val payload = "https://sandbox.taxcore.online/v/?vl=A0RCNz"
        val bytes = EscPos.document(ReceiptDocument(listOf("before"), payload, listOf("after")))
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue(text.indexOf("before") < text.indexOf(payload))
        assertTrue(text.indexOf(payload) < text.indexOf("after"))
        // GS ( k ... 1 P 0 <data>: store, with the length of data + 3.
        val store = text.indexOf("\u001D(k") .let { text.indexOf("1P0", it) }
        val length = payload.length + 3
        assertEquals((length and 0xFF).toChar(), text[store - 2])
        // GS ( k 3 0 1 Q 0: print.
        assertTrue(text.contains("\u001D(k\u0003\u00001Q0"))
    }

    @Test
    fun `a longer QR payload gets smaller modules so the code still fits the paper`() {
        assertTrue(EscPos.qrModuleSize(40) >= EscPos.qrModuleSize(400))
        assertTrue(EscPos.qrModuleSize(1000) >= 3)
    }
}
