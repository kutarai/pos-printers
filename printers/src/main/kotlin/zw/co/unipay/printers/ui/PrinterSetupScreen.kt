package zw.co.unipay.printers.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import zw.co.unipay.printers.BluetoothReceiptPrinter
import zw.co.unipay.printers.BuiltInPrinter
import zw.co.unipay.printers.PrintResult
import zw.co.unipay.printers.PrinterSettings
import zw.co.unipay.printers.PrinterTransport
import zw.co.unipay.printers.ReceiptPrinter
import zw.co.unipay.printers.WifiDirectPrinter
import zw.co.unipay.printers.WifiDirectReceiptPrinter

/**
 * One paper width an operator can choose.
 *
 * @param columns what the width means to a renderer — characters per line.
 * @param wifiOnly a sheet size (A4) is a network printer's, and a 58 mm thermal head handed the
 *   full-sheet layout prints eighty columns of wrapped rubbish; such widths are offered on the
 *   Wi-Fi Direct tab only and fall back to the widest roll when a Bluetooth printer is chosen.
 * @param note shown under the choices while it is selected.
 */
data class PaperWidthOption(
    val label: String,
    val columns: Int,
    val wifiOnly: Boolean = false,
    val note: String? = null,
) {
    companion object {
        val NARROW_ROLL = PaperWidthOption("58 mm", PrinterSettings.NARROW_ROLL)
        val WIDE_ROLL = PaperWidthOption("80 mm", PrinterSettings.WIDE_ROLL)

        /** The two thermal rolls every till and handheld has; add a sheet size where a renderer supports one. */
        val ROLLS: List<PaperWidthOption> = listOf(NARROW_ROLL, WIDE_ROLL)
    }
}

/**
 * Choosing the printer this device prints to, over Bluetooth or over Wi-Fi Direct.
 *
 * Pairing and joining networks is left to Android's own settings — both need a PIN or a button
 * press on the printer, both are done once, and a worse copy of a screen every operator already
 * knows would help nobody. This screen picks the printer, remembers it in [PrinterSettings], and
 * prints a specimen so the choice is proved before a customer is waiting for their receipt.
 *
 * Each transport keeps its own choice. A device that moves from one to the other and back finds
 * its first printer still remembered; whichever was chosen last is the one receipts go to.
 *
 * The same screen serves a shop till and a council handheld. What differs between them is passed
 * in: what the device is called in its messages, which paper widths its renderer can lay out,
 * and the specimen — rendered by the host exactly the way its real receipts are, so what is
 * proved here is the path a customer's receipt will take.
 *
 * @param specimen the lines to print for a given paper width, in the host's own receipt layout.
 * @param deviceNoun "till", "device", "terminal" — how the operator's messages name the hardware.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSetupScreen(
    onBack: () -> Unit,
    settings: PrinterSettings,
    bluetooth: BluetoothReceiptPrinter,
    wifiDirect: WifiDirectReceiptPrinter,
    printer: ReceiptPrinter,
    specimen: (columns: Int) -> List<String>,
    title: String = "Printer Setup",
    /**
     * A printer built into the device itself, when there is one. Null — the default — is what
     * every application had before, and keeps this screen about external printers only.
     *
     * Worth a parameter rather than a boolean: a device WITH a head needs its state said out
     * loud ("Out of paper" is a different job from "No printer fitted"), and a screen that
     * announced "this device has no printer" while a head sat in the operator's hand was simply
     * wrong — it only ever knew about the two radios.
     */
    builtIn: BuiltInPrinter? = null,
    deviceNoun: String = "device",
    paperWidths: List<PaperWidthOption> = PaperWidthOption.ROLLS,
    recordedNoun: String = "Receipts",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var transport by remember { mutableStateOf(settings.transport()) }
    var width by remember { mutableStateOf(settings.paperWidth()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Which tab is being looked at, which is not the same as which transport prints: looking at
    // Wi-Fi Direct must not stop a device printing over the Bluetooth printer it already has.
    var tab by remember { mutableStateOf(if (transport == PrinterTransport.WIFI_DIRECT) 1 else 0) }

    var bluetoothGranted by remember { mutableStateOf(bluetooth.hasPermission()) }
    var paired by remember {
        mutableStateOf(if (bluetoothGranted) bluetooth.pairedPrinters() else emptyList())
    }
    var chosenBluetooth by remember { mutableStateOf(settings.printerAddress()) }

    var wifiGranted by remember { mutableStateOf(wifiDirect.hasPermission()) }
    var peers by remember { mutableStateOf(emptyList<WifiDirectPrinter>()) }
    var scanning by remember { mutableStateOf(false) }
    var chosenWifi by remember { mutableStateOf(settings.wifiDirectAddress()) }
    var host by remember { mutableStateOf(settings.wifiDirectHost().orEmpty()) }
    var port by remember { mutableStateOf(settings.wifiDirectPort().toString()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        bluetoothGranted = bluetooth.hasPermission()
        wifiGranted = wifiDirect.hasPermission()
        paired = if (bluetoothGranted) bluetooth.pairedPrinters() else emptyList()
        if (tab == 0 && !bluetoothGranted) {
            message = "Without Bluetooth permission this $deviceNoun cannot reach a printer."
        }
        if (tab == 1 && !wifiGranted) {
            message = "Without permission to find nearby devices this $deviceNoun cannot reach a " +
                "Wi-Fi Direct printer."
        }
    }

    fun askForBluetooth() {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            arrayOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN,
            )
        }
        permissionLauncher.launch(wanted)
    }

    fun askForWifi() = permissionLauncher.launch(wifiDirect.requiredPermissions())

    // Asked for the tab being looked at rather than both at once: an operator setting up a
    // Bluetooth printer should not be made to answer a prompt about nearby Wi-Fi devices.
    LaunchedEffect(tab) {
        when {
            tab == 0 && !bluetoothGranted -> askForBluetooth()
            tab == 1 && !wifiGranted && wifiDirect.isSupported() -> askForWifi()
        }
    }

    fun chosenPort(): Int =
        port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: PrinterSettings.DEFAULT_PRINT_PORT

    /** The widest roll: where a sheet-only width has to fall back to when a roll printer is chosen. */
    fun widestRoll(): Int = paperWidths.filter { !it.wifiOnly }.maxOfOrNull { it.columns } ?: PrinterSettings.WIDE_ROLL
    fun isWifiOnly(columns: Int) = paperWidths.any { it.columns == columns && it.wifiOnly }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            InUseCard(
                configured = settings.isConfigured(),
                transport = transport,
                name = settings.printerName(),
                deviceNoun = deviceNoun,
                recordedNoun = recordedNoun,
                builtIn = builtIn,
            )

            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0; message = null }, text = { Text("Bluetooth") })
                Tab(selected = tab == 1, onClick = { tab = 1; message = null }, text = { Text("Wi-Fi Direct") })
            }

            Spacer(Modifier.height(16.dp))

            if (tab == 0) {
                if (!bluetooth.isBluetoothOn()) {
                    Warning(
                        title = "Bluetooth is switched off",
                        detail = "Switch it on to reach a printer.",
                        actionLabel = "Open Bluetooth settings",
                        onAction = { context.openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Text("Paired printers", style = MaterialTheme.typography.titleMedium)
                Hint("Pair a new printer in the $deviceNoun's Bluetooth settings, then choose it here.")
                Spacer(Modifier.height(8.dp))

                when {
                    !bluetoothGranted -> Button(onClick = { askForBluetooth() }) { Text("Allow Bluetooth") }
                    paired.isEmpty() -> {
                        Text("Nothing is paired with this $deviceNoun yet.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { context.openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) }) {
                            Text("Open Bluetooth settings")
                        }
                    }
                    else -> paired.forEach { device ->
                        PrinterRow(
                            name = device.name,
                            detail = device.address,
                            selected = device.address == chosenBluetooth,
                            onSelect = {
                                chosenBluetooth = device.address
                                // A sheet size cannot follow the operator onto a thermal roll.
                                if (isWifiOnly(width)) width = widestRoll()
                                settings.choosePrinter(device, width)
                                transport = PrinterTransport.BLUETOOTH
                                message = "${device.name} will print this $deviceNoun's receipts."
                            },
                        )
                    }
                }

                if (chosenBluetooth != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        settings.forgetPrinter(PrinterTransport.BLUETOOTH)
                        chosenBluetooth = null
                        transport = settings.transport()
                        message = "The Bluetooth printer has been forgotten."
                    }) { Text("Forget this Bluetooth printer") }
                }
            } else {
                if (!wifiDirect.isSupported()) {
                    Warning(
                        title = "This $deviceNoun has no Wi-Fi Direct",
                        detail = "Use a Bluetooth printer on this $deviceNoun.",
                    )
                } else {
                    if (!wifiDirect.isWifiOn()) {
                        Warning(
                            title = "Wi-Fi is switched off",
                            detail = "Wi-Fi Direct uses the same radio, so it cannot reach a printer until Wi-Fi is on.",
                            actionLabel = "Open Wi-Fi settings",
                            onAction = { context.openSettings(android.provider.Settings.ACTION_WIFI_SETTINGS) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Text("Printers in range", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "Put the printer into Wi-Fi Direct mode, then search. Joining it takes a moment " +
                            "the first time a receipt is printed."
                    )
                    Spacer(Modifier.height(8.dp))

                    if (!wifiGranted) {
                        Button(onClick = { askForWifi() }) { Text("Allow nearby devices") }
                    } else {
                        Button(
                            enabled = !scanning,
                            onClick = {
                                scanning = true
                                message = null
                                scope.launch {
                                    peers = wifiDirect.discoverPrinters()
                                    scanning = false
                                    if (peers.isEmpty()) {
                                        message = "Nothing answered. Check the printer is switched on and in Wi-Fi Direct mode."
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (scanning) "Searching…" else "Search for printers")
                        }

                        if (scanning) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Spacer(Modifier.height(8.dp))

                        peers.forEach { peer ->
                            PrinterRow(
                                name = peer.name,
                                detail = if (peer.isPrinter) "${peer.address} · printer" else peer.address,
                                selected = peer.address == chosenWifi,
                                onSelect = {
                                    chosenWifi = peer.address
                                    settings.chooseWifiDirectPrinter(
                                        printer = peer,
                                        host = host.trim().ifBlank { null },
                                        port = chosenPort(),
                                        paperWidth = width,
                                    )
                                    transport = PrinterTransport.WIFI_DIRECT
                                    message = "${peer.name} will print this $deviceNoun's receipts."
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Text("By address", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "Some printers sold as Wi-Fi Direct are really small access points: join the " +
                            "printer's network in Wi-Fi settings and enter the address it prints on its " +
                            "own test page."
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Address") },
                            placeholder = { Text("192.168.49.1") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { char -> char.isDigit() }.take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = host.isNotBlank(),
                        onClick = {
                            settings.chooseWifiDirectPrinter(
                                printer = null,
                                host = host.trim(),
                                port = chosenPort(),
                                paperWidth = width,
                            )
                            chosenWifi = null
                            transport = PrinterTransport.WIFI_DIRECT
                            message = "Receipts will be printed to ${host.trim()}:${chosenPort()}."
                        },
                    ) { Text("Use this address") }

                    if (chosenWifi != null || settings.wifiDirectHost() != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            settings.forgetPrinter(PrinterTransport.WIFI_DIRECT)
                            chosenWifi = null
                            host = ""
                            transport = settings.transport()
                            message = "The Wi-Fi Direct printer has been forgotten."
                        }) { Text("Forget this Wi-Fi Direct printer") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("Paper width", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                paperWidths
                    .filter { !it.wifiOnly || tab == 1 }
                    .forEach { option ->
                        FilterChip(
                            selected = width == option.columns,
                            onClick = {
                                width = option.columns
                                settings.setPaperWidth(option.columns)
                            },
                            label = { Text(option.label) },
                        )
                    }
            }
            paperWidths.firstOrNull { it.columns == width }?.note?.let { note ->
                Spacer(Modifier.height(4.dp))
                Hint(note)
            }

            Spacer(Modifier.height(20.dp))

            Button(
                enabled = (settings.isConfigured() || builtIn?.ready == true) && !busy,
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        // Rendered by the host and sent through the same router its receipts use,
                        // so what is proved here is the path a customer's receipt will take —
                        // including whether the paper is the width it was said to be.
                        // No external printer chosen, but the device has a head: prove that one.
                        message = if (!settings.isConfigured() && builtIn != null) {
                            builtIn.printSpecimen()
                        } else when (val result = printer.printLines(specimen(width))) {
                            is PrintResult.Printed -> "Specimen printed."
                            is PrintResult.Failed -> result.reason
                        }
                        busy = false
                    }
                },
            ) {
                Icon(Icons.Filled.Print, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Printing…" else "Print a specimen")
            }

            message?.let {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun android.content.Context.openSettings(action: String) {
    startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * What this device prints to as things stand.
 *
 * Worth the space: with two transports on offer, a screen that only showed what was selected on
 * the tab being looked at would leave an operator unsure which printer the next receipt goes to.
 */
@Composable
private fun InUseCard(
    configured: Boolean,
    transport: PrinterTransport,
    name: String?,
    deviceNoun: String,
    recordedNoun: String,
    builtIn: BuiltInPrinter?,
) {
    val over = when (transport) {
        PrinterTransport.BLUETOOTH -> "Bluetooth"
        PrinterTransport.WIFI_DIRECT -> "Wi-Fi Direct"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            when {
                // An external printer chosen deliberately is what receipts go to, even on a
                // device with a head of its own: the operator picked it.
                configured -> {
                    Text("Receipts print over $over", fontWeight = FontWeight.Bold)
                    Hint(name ?: "the printer chosen below")
                }
                builtIn != null -> {
                    Text(builtIn.name, fontWeight = FontWeight.Bold)
                    Hint(
                        if (builtIn.ready) "$recordedNoun print here unless a printer is chosen below."
                        else "${builtIn.status}. $recordedNoun are recorded either way."
                    )
                }
                else -> {
                    Text("This $deviceNoun has no printer", fontWeight = FontWeight.Bold)
                    Hint("$recordedNoun are recorded either way; nothing is printed until a printer is chosen.")
                }
            }
        }
    }
}

@Composable
private fun Warning(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun PrinterRow(name: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Hint(detail)
        }
    }
}
