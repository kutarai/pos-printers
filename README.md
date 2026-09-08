# Printers

Putting a receipt on paper, from any Android device, over whatever radio reaches the printer.

Bluetooth (serial port profile) and Wi-Fi Direct (raw ESC/POS over TCP), the settings that
remember which printer a device uses and how wide its paper is, the ESC/POS bytes, a plain-text
receipt layout, and the one Printer Setup screen every application shows. Nothing in here knows
what is being printed: a host renders its own receipt to lines and hands them over.

Used by the Synergy POS till and the ProCity council handheld. It is a library rather than part of
either because both stand next to the same printers, and two copies of a Bluetooth socket drift
apart in exactly the places that stop a receipt coming out.

## What is in it

| Where | What it holds |
|---|---|
| `PrinterSettings`, `PrinterTransport` | Which printer this device prints to, how it is reached, its paper width — one choice per transport |
| `ReceiptPrinter`, `TillReceiptPrinter`, `PrintResult` | The port a host prints through, and the router that settles the transport from the settings |
| `BluetoothReceiptPrinter`, `WifiDirectReceiptPrinter` | The two transports; discovery, permissions, the socket |
| `EscPos` | The control sequences |
| `ReceiptLayout`, `ReceiptContent`, `ReceiptLine` | A plain-text receipt at 32 or 48 columns, for hosts without a renderer of their own |
| `ui.PrinterSetupScreen`, `ui.PaperWidthOption` | The setup screen (Material 3): choose the printer, the paper, print a specimen |

## Using it

Point a project directory at it from the application's `settings.gradle.kts`:

```kotlin
include(":printers")
project(":printers").projectDir = file("/path/to/shared/Printers/printers")
```

then `implementation(project(":printers"))`. The host wires the pieces once at start-up:

```kotlin
val settings = PrinterSettings(context)
val printer: ReceiptPrinter = TillReceiptPrinter(
    settings,
    BluetoothReceiptPrinter(context, settings),
    WifiDirectReceiptPrinter(context, settings),
)
```

and shows the setup screen with its own specimen, device noun and paper widths:

```kotlin
PrinterSetupScreen(
    onBack = ..., settings = settings, bluetooth = ..., wifiDirect = ..., printer = printer,
    deviceNoun = "till",
    paperWidths = PaperWidthOption.ROLLS,                    // add a wifiOnly A4 option if the renderer has one
    specimen = { columns -> myRenderer.render(specimen, columns) },
)
```

The settings file on the device keeps the name it had when this code lived in Payments, so a
device upgraded to a build that uses this library keeps the printer it already had.

## Building on its own

```
./gradlew :printers:assembleDebug :printers:testDebugUnitTest
```

The tests are the wire formats — receipt widths, ESC/POS sequences, the transport names read
back from settings. They need no device.
