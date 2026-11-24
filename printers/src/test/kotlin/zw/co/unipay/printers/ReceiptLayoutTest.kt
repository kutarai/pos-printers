package zw.co.unipay.printers




import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A receipt as it comes off a roll of till paper.
 *
 * The paper is a fixed number of characters wide and nothing wraps gracefully: a line one
 * character too long is broken by the printer wherever it happens to run out, which puts a price
 * on the next line where nobody looks for it. So the width is enforced here, where it can be
 * tested, rather than discovered on a customer's receipt.
 */
class ReceiptLayoutTest {

    private val paper = ReceiptLayout(width = 32)

    // ── Fitting the paper ──────────────────────────────────────────────

    @Test
    fun `a label and an amount sit on one line, pushed to the edges`() {
        assertEquals(
            "Total" + " ".repeat(32 - "Total".length - "12.50".length) + "12.50",
            paper.spread("Total", "12.50")
        )
    }

    @Test
    fun `a label too long for its amount is cut, never wrapped`() {
        // The amount is what must survive: a customer checks the figure, and a printer that
        // wraps puts it on a line of its own where it reads as a separate charge.
        val line = paper.spread("Chicken Pieces Family Pack Large", "104.75")

        assertEquals(32, line.length)
        assertTrue("the amount must survive", line.endsWith("104.75"))
    }

    @Test
    fun `centring a line puts it in the middle of the paper`() {
        val centred = paper.centre("SYNERGY RETAIL")
        assertEquals(" ".repeat((32 - "SYNERGY RETAIL".length) / 2) + "SYNERGY RETAIL", centred)
    }

    @Test
    fun `something wider than the paper is simply cut to it`() {
        assertEquals(32, paper.centre("x".repeat(50)).length)
    }

    @Test
    fun `a rule fills the width`() {
        assertEquals("-".repeat(32), paper.rule())
    }

    // ── The lines of a sale ────────────────────────────────────────────

    @Test
    fun `an item is the description, then quantity and price left and the amount right`() {
        val lines = paper.item(name = "Beef Burger", quantity = 2.0, unitPrice = 3.5, lineTotal = 7.0)

        assertEquals(listOf("Beef Burger", "2 x 3.50" + " ".repeat(32 - 8 - 4) + "7.00"), lines)
    }

    @Test
    fun `a single unit is laid out the same way`() {
        val lines = paper.item(name = "Chips (Regular)", quantity = 1.0, unitPrice = 1.5, lineTotal = 1.5)

        assertEquals(listOf("Chips (Regular)", "1 x 1.50" + " ".repeat(32 - 8 - 4) + "1.50"), lines)
    }

    @Test
    fun `a weighed item keeps the weight that was charged for`() {
        // 0.756 kg at 6.50 is not "1 x 4.91": the customer is checking the scale, and a receipt
        // that hides the weight cannot be argued with at the counter.
        val lines = paper.item(
            name = "Beef Mince", quantity = 0.756, unitPrice = 6.50, lineTotal = 4.91,
            isWeighted = true, unitOfMeasure = "kg"
        )

        assertEquals("Beef Mince", lines.first())
        assertEquals("0.756 kg x 6.50" + " ".repeat(32 - 15 - 4) + "4.91", lines[1])
    }

    @Test
    fun `a description wider than the paper wraps rather than pushing the amount off`() {
        val lines = paper.item(name = "Amoxicillin 500mg capsules pack of 21", quantity = 1.0, unitPrice = 33.6, lineTotal = 33.6)

        assertTrue(lines.all { it.length <= 32 })
        assertTrue(lines.last().endsWith("33.60"))
    }

    // ── What the printer is handed ─────────────────────────────────────

    @Test
    fun `every line of a rendered receipt fits the paper`() {
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail — Harare Branch 10 Downtown",
                receiptNumber = "REC-000045",
                dateTime = "04 Aug 2026 10:15",
                terminalId = "A50-42GB-4F00315",
                lines = listOf(
                    ReceiptLine("Beef Burger", 2.0, 3.50, 7.00),
                    ReceiptLine("Chicken Pieces", 0.756, 6.50, 4.91, isWeighted = true, unitOfMeasure = "kg")
                ),
                subtotal = 10.36, tax = 1.55, total = 11.91,
                currency = "USD",
                tendered = 20.00, change = 8.09,
                paymentMethod = "CASH"
            )
        )

        for (line in receipt) {
            assertTrue("too wide for the paper: '$line'", line.length <= 32)
        }
    }

    @Test
    fun `a receipt says what it is, what was bought, and what was paid`() {
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail",
                receiptNumber = "REC-000045",
                dateTime = "04 Aug 2026 10:15",
                terminalId = "A50-42GB-4F00315",
                lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
                subtotal = 1.30, tax = 0.20, total = 1.50,
                currency = "USD",
                tendered = 2.00, change = 0.50,
                paymentMethod = "CASH"
            )
        ).joinToString("\n")

        assertTrue("the shop", receipt.contains("Synergy Retail"))
        assertTrue("the receipt number", receipt.contains("REC-000045"))
        assertTrue("the date", receipt.contains("04 Aug 2026 10:15"))
        assertTrue("the item", receipt.contains("Chips (Regular)"))
        assertTrue("the total", receipt.contains("TOTAL"))
        assertTrue("the currency", receipt.contains("USD"))
        assertTrue("what was tendered", receipt.contains("Cash"))
        assertTrue("the change", receipt.contains("Change"))
        assertTrue("which till", receipt.contains("A50-42GB-4F00315"))
    }

    @Test
    fun `VAT is shown as contained in the price, not added to it`() {
        // Prices here are VAT-inclusive everywhere else in the system; a receipt that reads as
        // though VAT were added on top invites an argument the shop will lose.
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail",
                receiptNumber = "REC-1", dateTime = "04 Aug 2026", terminalId = "T1",
                lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
                subtotal = 1.30, tax = 0.20, total = 1.50, currency = "USD",
                tendered = 1.50, change = 0.0, paymentMethod = "CASH"
            )
        ).joinToString("\n")

        assertTrue("VAT should be described as included", receipt.contains("VAT included"))
    }

    @Test
    fun `a card sale shows no change line`() {
        val receipt = paper.render(
            ReceiptContent(
                merchantName = "Synergy Retail",
                receiptNumber = "REC-2", dateTime = "04 Aug 2026", terminalId = "T1",
                lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
                subtotal = 1.30, tax = 0.20, total = 1.50, currency = "USD",
                tendered = 1.50, change = 0.0, paymentMethod = "CARD"
            )
        ).joinToString("\n")

        assertTrue(receipt.contains("Card"))
        assertTrue("no change was given, so no change line", !receipt.contains("Change"))
    }

    @Test
    fun `a wider roll uses the width it has`() {
        val wide = ReceiptLayout(width = 48)

        assertEquals(48, wide.rule().length)
        assertEquals("Total" + " ".repeat(48 - "Total".length - "12.50".length) + "12.50",
            wide.spread("Total", "12.50"))
    }

    private fun cashSale(tendered: Double = 100.0, change: Double = 40.0) = ReceiptContent(
        merchantName = "Synergy Retail",
        receiptNumber = "REC-4", dateTime = "04 Aug 2026", terminalId = "POS-01",
        lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
        subtotal = 1.30, tax = 0.20, total = 1.50, currency = "USD",
        tendered = tendered, change = change, paymentMethod = "CASH"
    )

    @Test
    fun `foreignChangeIsLabelled`() {
        val receipt = paper.render(cashSale().copy(changeCurrency = "ZWG"))

        assertTrue(receipt.any { it.startsWith("Change (ZWG)") && it.endsWith("40.00") })
    }

    @Test
    fun `foreignTenderIsLabelled`() {
        val receipt = paper.render(cashSale().copy(tenderedCurrency = "ZWG"))

        assertTrue(receipt.any { it.startsWith("Cash (ZWG)") && it.endsWith("100.00") })
    }

    @Test
    fun `sameCurrencyChangeUnchanged`() {
        val receipt = paper.render(cashSale().copy(changeCurrency = "USD", tenderedCurrency = "USD"))

        assertTrue(receipt.any { it.startsWith("Change") && !it.contains("(") })
        assertTrue(receipt.any { it.startsWith("Cash") && !it.contains("(") })
    }

    @Test
    fun `noChangeLineWhenZero`() {
        val receipt = paper.render(cashSale(change = 0.0).copy(changeCurrency = "ZWG"))

        assertTrue(receipt.none { it.startsWith("Change") })
    }

    private fun contentWith(cashier: String? = null) = ReceiptContent(
        merchantName = "Synergy Retail",
        receiptNumber = "REC-3", dateTime = "04 Aug 2026", terminalId = "POS-01",
        lines = listOf(ReceiptLine("Chips (Regular)", 1.0, 1.50, 1.50)),
        subtotal = 1.30, tax = 0.20, total = 1.50, currency = "USD",
        tendered = 1.50, change = 0.0, paymentMethod = "CASH"
    ).let { if (cashier == null) it else it.copy(cashier = cashier) }

    @Test
    fun `cashierLinePrintsAfterTill`() {
        val receipt = paper.render(contentWith(cashier = "Tendai Moyo"))

        val till = receipt.indexOf("Till: POS-01")
        assertTrue("the till line is there", till >= 0)
        assertEquals("Cashier: Tendai Moyo", receipt[till + 1])
    }

    @Test
    fun `noCashierNoLine`() {
        val receipt = paper.render(contentWith())

        assertTrue(receipt.none { it.startsWith("Cashier:") })
    }

    @Test
    fun `a long cashier name is cut to the paper`() {
        val receipt = paper.render(contentWith(cashier = "Tendai Moyo-Chikwanha the Third of Harare"))

        assertTrue(receipt.all { it.length <= 32 })
    }

    // ── Orders, line notes, claims ─────────────────────────────────────

    @Test
    fun plainReceiptUnchanged() {
        val receipt = paper.document(contentWith(cashier = "Tendai Moyo")).lines

        assertEquals(
            listOf(
                "         Synergy Retail",
                "          04 Aug 2026",
                "--------------------------------",
                "Receipt: REC-3",
                "Till: POS-01",
                "Cashier: Tendai Moyo",
                "--------------------------------",
                "Chips (Regular)",
                "1 x 1.50                    1.50",
                "--------------------------------",
                "Subtotal                    1.30",
                "VAT included                0.20",
                "TOTAL USD                   1.50",
                "--------------------------------",
                "Cash                        1.50",
                "",
                " Thank you for shopping with us"
            ),
            receipt
        )
    }

    // The sample label is 27 characters: with "-7.00" it cannot fit 32 columns, where spread
    // would cut it, so the claim lines are read off a wider roll.
    private val wide = ReceiptLayout(width = 48)

    private val claim = ReceiptClaim("Medical aid (CIMAS · CLM-1)", 7.0)

    private fun claimed() = contentWith().copy(
        total = 10.0, claims = listOf(claim), amountDue = 3.0
    )

    @Test
    fun orderLinesPrintUnderCashier() {
        val receipt = paper.render(
            contentWith(cashier = "Tendai Moyo").copy(orderLines = listOf("Table 12"))
        )

        val cashier = receipt.indexOfFirst { it.startsWith("Cashier:") }
        assertEquals("Table 12", receipt[cashier + 1])
    }

    @Test
    fun lineNotesPrintBeneathTheLine() {
        val base = contentWith()
        val receipt = paper.render(
            base.copy(lines = listOf(ReceiptLine("Steak", 1.0, 5.0, 5.0, notes = "rare")))
        )

        val price = receipt.indexOfFirst { it.startsWith("1 x 5.00") }
        assertTrue("the price line is there", price >= 0)
        assertEquals("  rare", receipt[price + 1])
    }

    @Test
    fun claimAndToPayAfterTotal() {
        val receipt = wide.render(claimed())

        val total = receipt.indexOfFirst { it.startsWith("TOTAL") }
        val claimAt = receipt.indexOfFirst { it.startsWith("Medical aid (CIMAS · CLM-1)") }
        assertTrue(claimAt > total)
        assertTrue(receipt[claimAt].endsWith("-7.00"))
        assertTrue(receipt[claimAt + 1].startsWith("TO PAY"))
        assertTrue(receipt[claimAt + 1].endsWith("3.00"))
    }

    @Test
    fun toPayNamesForeignCurrency() {
        val receipt = wide.render(claimed().copy(amountDueCurrency = "ZWG"))

        assertTrue(receipt.any { it.startsWith("TO PAY (ZWG)") })
    }

    @Test
    fun claimsPrintWhenFiscalReplacesItems() {
        val fiscal = FiscalSection(lines = listOf("X"), qrCode = null, replacesItems = true)
        val receipt = wide.render(claimed().copy(fiscal = fiscal))

        assertTrue(receipt.any { it.startsWith("Medical aid (CIMAS · CLM-1)") })
        assertTrue(receipt.any { it.startsWith("TO PAY") })
    }

    @Test
    fun noTenderLineWhenNothingTendered() {
        val receipt = paper.render(claimed().copy(paymentMethod = "", tendered = 0.0))

        assertTrue(receipt.any { it.startsWith("Medical aid") })
        assertTrue(receipt.none { it.startsWith("Cash") })
    }
}
