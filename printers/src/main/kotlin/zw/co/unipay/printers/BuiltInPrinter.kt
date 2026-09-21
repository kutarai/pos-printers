package zw.co.unipay.printers

/**
 * A printer built into the device the application is running on, as the setup screen needs it.
 *
 * Deliberately plain data plus one lambda: this library knows about Bluetooth and Wi-Fi Direct,
 * and nothing about any vendor's built-in head. The host application owns that driver and
 * answers these three questions on its behalf, so the screen can report a head it cannot
 * itself reach.
 *
 * @param name what to call it on screen, e.g. "Built-in printer".
 * @param status one short phrase for its state — "Ready", "Out of paper".
 * @param ready whether a receipt sent now would come out.
 * @param printSpecimen prints a specimen on it and returns the message to show.
 */
data class BuiltInPrinter(
    val name: String,
    val status: String,
    val ready: Boolean,
    val printSpecimen: suspend () -> String,
)
