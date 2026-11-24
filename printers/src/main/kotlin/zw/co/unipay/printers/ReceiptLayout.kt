package zw.co.unipay.printers

/** One line of a sale, as it appears on paper. */
data class ReceiptLine(
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    val lineTotal: Double,
    val isWeighted: Boolean = false,
    val unitOfMeasure: String = "Each",
    /** Free text for the line, such as "rare"; printed beneath it, indented. */
    val notes: String = ""
)

/** A share of the bill paid by someone else, such as a medical aid. */
data class ReceiptClaim(val label: String, val amount: Double)

/** Everything a receipt says, gathered before anything is formatted. */
data class ReceiptContent(
    val merchantName: String,
    val receiptNumber: String,
    val dateTime: String,
    val terminalId: String,
    /** The cashier's full name; blank prints no cashier line. */
    val cashier: String = "",
    val lines: List<ReceiptLine>,
    val subtotal: Double,
    val tax: Double,
    val total: Double,
    val currency: String,
    val tendered: Double,
    val change: Double,
    /** Currency the cash was handed over in; blank or the sale's own prints no currency. */
    val tenderedCurrency: String = "",
    /** Currency the change was given in; blank or the sale's own prints no currency. */
    val changeCurrency: String = "",
    val paymentMethod: String,
    val footer: String = "Thank you for shopping with us",
    /** What the tax authority made of the sale, printed as it came; null where none is required. */
    val fiscal: FiscalSection? = null,
    /** Lines naming the order the sale came from (table, patient); printed after the cashier. */
    val orderLines: List<String> = emptyList(),
    /** Shares of the bill paid by others; each prints as a deduction before the tender. */
    val claims: List<ReceiptClaim> = emptyList(),
    /** What the customer still owes; printed as TO PAY only when [claims] is not empty. */
    val amountDue: Double? = null,
    /** Currency [amountDue] is in; named in the label when it differs from [currency]. */
    val amountDueCurrency: String = ""
)

/**
 * The fiscal part of a receipt, from the tax authority's system: lines to print as they are,
 * the QR code that lets the customer check the receipt, and lines after it. When
 * [replacesItems], the lines already list the items and totals in the authority's own format,
 * so the till's own list is left out rather than printed twice.
 */
data class FiscalSection(
    val lines: List<String>,
    val qrCode: String?,
    val linesAfterQr: List<String> = emptyList(),
    val replacesItems: Boolean = false
)

/** What goes to the printer: lines, a QR code, then more lines. */
data class ReceiptDocument(
    val lines: List<String>,
    val qrCode: String? = null,
    val linesAfterQr: List<String> = emptyList()
)

/**
 * Lays a receipt out for a roll of till paper.
 *
 * The paper is a fixed number of characters wide and nothing wraps gracefully: a line one
 * character too long is broken wherever the printer happens to run out, which puts half a price
 * on the next line where nobody looks for it. Everything here is therefore cut to the width
 * deliberately, and the amount is what survives — a customer checks the figure.
 *
 * Kept free of Android and of the printer itself so the layout can be tested as text, which is
 * the only way to find a line that is one character too wide without feeding paper through.
 */
class ReceiptLayout(private val width: Int = 32) {

    /** A label on the left and an amount on the right, filling one line exactly. */
    fun spread(label: String, amount: String): String {
        val room = width - amount.length - 1
        if (room <= 0) return amount.takeLast(width)

        val cut = if (label.length > room) label.take(room) else label
        return cut.padEnd(width - amount.length) + amount
    }

    fun centre(text: String): String {
        if (text.length >= width) return text.take(width)
        val pad = (width - text.length) / 2
        return " ".repeat(pad) + text
    }

    fun rule(): String = "-".repeat(width)

    /**
     * An item, on two lines: what it was, then the quantity and unit price on the left and what
     * the line came to on the right, every amount to two decimals. The same shape whatever the
     * quantity, so the eye runs down one column of amounts; a weight keeps its unit, because the
     * customer is checking the scale.
     */
    fun item(
        name: String,
        quantity: Double,
        unitPrice: Double,
        lineTotal: Double,
        isWeighted: Boolean = false,
        unitOfMeasure: String = "Each"
    ): List<String> {
        val shownQuantity = if (isWeighted) "${trimZeros(quantity)} $unitOfMeasure" else trimZeros(quantity)
        return wrap(name) + spread("$shownQuantity x ${money(unitPrice)}", money(lineTotal))
    }

    /**
     * A line of the tax authority's text, laid out for this paper. TaxCore's journal is 40
     * characters wide, and on a 32-column roll split at the paper's edge its rules and banners
     * broke in two and every "label   value" pair landed on separate lines. Only the spacing and
     * the padding of rules change here; every word and figure is printed:
     *
     *  - a rule (one character repeated) becomes a rule of the paper's width;
     *  - a banner (text padded with rule characters) is re-centred in the same padding;
     *  - an item's working ("  2.0 x 4.6    9.20") becomes the till's own item line, amounts to
     *    two decimals;
     *  - a label and a value set apart by two or more spaces are spread across the paper;
     *  - anything else that is too wide is wrapped at a space ([wrap]).
     */
    fun fit(text: String): List<String> {
        RULE.matchEntire(text)?.let { return listOf(it.groupValues[1].repeat(width)) }
        BANNER.matchEntire(text)?.let { m ->
            val pad = m.groupValues[1].first()
            val title = " ${m.groupValues[2]} "
            if (title.length >= width) return wrap(m.groupValues[2])
            val left = (width - title.length) / 2
            return listOf(pad.toString().repeat(left) + title + pad.toString().repeat(width - left - title.length))
        }
        WORKING.matchEntire(text)?.let { m ->
            val quantity = m.groupValues[1].toDoubleOrNull()
            val price = m.groupValues[2].toDoubleOrNull()
            val total = m.groupValues[3].replace(",", "").toDoubleOrNull()
            if (quantity != null && price != null && total != null) {
                return listOf(spread("${trimZeros(quantity)} x ${money(price)}", money(total)))
            }
        }
        if (text.length <= width) return listOf(text)
        PAIR.matchEntire(text)?.let { m ->
            val label = m.groupValues[1]
            val value = m.groupValues[2]
            if (label.length + 1 + value.length <= width) return listOf(spread(label, value))
        }
        return wrap(text)
    }

    /** The receipt as text alone; a fiscal QR code is left out. */
    fun render(content: ReceiptContent): List<String> = document(content).let { it.lines + it.linesAfterQr }

    /**
     * Breaks [text] into lines that fit, at spaces where it can. A fiscal line can be wider than
     * the paper - TaxCore's text is 40 characters - and every character of it has to be printed.
     */
    fun wrap(text: String): List<String> {
        if (text.length <= width) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > width) {
            val space = rest.lastIndexOf(' ', width)
            val cut = if (space > width / 2) space else width
            out += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    fun document(content: ReceiptContent): ReceiptDocument {
        val out = mutableListOf<String>()
        val fiscal = content.fiscal

        out += centre(content.merchantName.take(width))
        out += centre(content.dateTime)
        out += rule()
        out += "Receipt: ${content.receiptNumber}".take(width)
        out += "Till: ${content.terminalId}".take(width)
        if (content.cashier.isNotBlank()) out += "Cashier: ${content.cashier}".take(width)
        content.orderLines.forEach { out += fit(it) }
        out += rule()

        if (fiscal?.replacesItems != true) {
            for (line in content.lines) {
                out += item(
                    line.name, line.quantity, line.unitPrice, line.lineTotal,
                    line.isWeighted, line.unitOfMeasure
                )
                if (line.notes.isNotBlank()) {
                    ReceiptLayout(width - NOTE_INDENT.length).wrap(line.notes.trim())
                        .forEach { out += NOTE_INDENT + it }
                }
            }

            out += rule()
            out += spread("Subtotal", money(content.subtotal))
            // Prices are VAT-inclusive everywhere in this system. A receipt reading as though VAT
            // were added on top invites an argument at the counter that the shop will lose.
            out += spread("VAT included", money(content.tax))
            out += spread("TOTAL ${content.currency}", money(content.total))
            out += rule()
        }

        if (content.claims.isNotEmpty()) {
            content.claims.forEach { out += spread(it.label, "-" + money(it.amount)) }
            content.amountDue?.let {
                val label = "TO PAY" + foreign(content.amountDueCurrency, content.currency)
                out += spread(label, money(it))
            }
        }

        // Nothing tendered and no method: the medical aid covered the whole bill.
        if (content.paymentMethod.isNotBlank() || content.tendered != 0.0) {
            out += spread(
                tenderLabel(content.paymentMethod) + foreign(content.tenderedCurrency, content.currency),
                money(content.tendered)
            )
        }
        if (content.change > 0.0) {
            out += spread(
                "Change" + foreign(content.changeCurrency, content.currency),
                money(content.change)
            )
        }

        if (fiscal == null) {
            out += ""
            out += centre(content.footer)
            return ReceiptDocument(out)
        }

        // The fiscal part last: the authority's text says where it ends, and nothing of the
        // till's may be mistaken for part of it.
        out += rule()
        fiscal.lines.forEach { out += fit(it) }
        val after = mutableListOf<String>()
        fiscal.linesAfterQr.forEach { after += fit(it) }
        after += ""
        after += centre(content.footer)
        return ReceiptDocument(out, fiscal.qrCode, after)
    }

    /** " (ZWG)" when [given] is a currency other than the sale's, otherwise nothing. */
    private fun foreign(given: String, sale: String): String =
        if (given.isNotBlank() && given != sale) " ($given)" else ""

    private fun tenderLabel(method: String): String = when (method.uppercase()) {
        "CASH" -> "Cash"
        "CARD" -> "Card"
        "MOBILE_MONEY", "MOBILEMONEY" -> "Mobile money"
        "QR" -> "QR"
        else -> method.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun money(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    private companion object {
        const val NOTE_INDENT = "  "

        /** One rule character, repeated: "====", "----". */
        val RULE = Regex("""^\s*([=\-*_#~])\1{3,}\s*$""")

        /** Text between runs of one rule character: "===== TITLE =====". */
        val BANNER = Regex("""^\s*([=\-*_#~])\1{2,}\s*(\S.*?\S|\S)\s*\1{3,}\s*$""")

        /** An item's working: quantity x price, then the line's amount. */
        val WORKING = Regex("""^\s*(\d+(?:\.\d+)?)\s*[xX×]\s*(\d+(?:\.\d+)?)\s{2,}(-?[\d,]+(?:\.\d+)?)\s*$""")

        /** A label and a value, set apart by two or more spaces. */
        val PAIR = Regex("""^\s*(\S.*?)\s{2,}(\S.*?)\s*$""")
    }

    /** 3.0 reads as "3"; 0.756 keeps every digit that was weighed. */
    private fun trimZeros(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else value.toString().trimEnd('0').trimEnd('.')
}
