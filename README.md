# Bluetooth Inspector Pro — Protocol Lab Edition

Professional Android BLE/GATT inspection and controlled protocol-research workspace.

## What this edition adds

- BLE scan, RSSI, TX power, connectability and advertisement inspection
- GATT service/characteristic discovery
- READ, WRITE and WRITE_NO_RESPONSE
- NOTIFY / INDICATE subscription
- **Protocol Lab** with exact HEX command composition
- HEX ↔ ASCII conversion
- CRC-8, CRC-16/IBM and CRC-32 calculators
- Controlled protocol discovery around a user-provided seed command
- Hard limit of **32 probe candidates per run** plus an always-available STOP action
- Timestamped evidence log for writes, reads, notifications, descriptor events and probe plans
- Export of the protocol evidence log as JSON
- Saved exact HEX commands in app preferences
- Android 12+ Bluetooth runtime permissions and Android 8–11 location permission for BLE scanning

## Important: what “discover all commands” means

GATT properties tell us whether a characteristic can be read, written, or used for notifications. They **do not expose the private meaning of the bytes** used by a vendor/device protocol.

Therefore the Protocol Lab deliberately separates:

1. **Observed facts** — UUID, properties, write result, returned notification/read bytes.
2. **Candidate commands** — generated mutations that still need to be tested.
3. **User/vendor knowledge** — labels such as “power”, “mode”, “speed”, etc.

The app does not invent a command dictionary from a UUID. To obtain a high-confidence command map, collect evidence from the device while testing one controlled change at a time, or provide the vendor protocol/manual/known-good command captures.

## Current device evidence from the supplied inspection

The supplied GATT inspection showed a custom primary service:

`00001000-0000-1000-8000-00805f9b34fb`

with:

- `00001001-0000-1000-8000-00805f9b34fb` — READ, WRITE, WRITE_NO_RESPONSE
- `00001002-0000-1000-8000-00805f9b34fb` — NOTIFY

That makes `00001001` a strong **candidate TX/control channel** and `00001002` a strong **candidate RX/event channel**, but this is not proof of the command semantics.

The inspection also showed HID service `00001812-0000-1000-8000-00805f9b34fb` and Battery Service `0000180f-0000-1000-8000-00805f9b34fb`.

## Using Protocol Lab

1. Connect to the BLE device.
2. Open **GATT** and confirm services/characteristics.
3. Open **Protocol Lab**.
4. Select the writable characteristic.
5. Select the notify/indicate characteristic if available and tap **SUBSCRIBE RX**.
6. Enter the exact HEX command.
7. Use **WRITE** for acknowledged writes or **WRITE NO RESPONSE** when the characteristic supports it.
8. Watch the evidence log for `WRITE_RESULT`, `READ`, and `NOTIFY` events.
9. For controlled discovery, enter a known seed and tap **Build Candidates**. The app creates a small mutation set and never exceeds 32 probes in one run.
10. Export the evidence log and use it to build a verified command table.

## Precision notes

- HEX is the authoritative representation. No implicit newline or terminator is appended.
- The app chooses the requested write type explicitly.
- Notifications are captured with UUID, HEX, printable text and timestamp.
- CRC tools are calculators only. Do not append a CRC unless evidence shows the device expects one.
- A successful GATT write means the transport accepted the write; it does **not** mean the device executed a semantic command.

## Build

Requirements:

- Android Studio Ladybug or newer
- JDK 17
- Gradle 8.13
- Android SDK 36
- Android 8.0 / API 26+

GitHub Actions provisions Gradle 8.13 and builds the debug APK automatically.

```bash
gradle :app:assembleDebug --stacktrace
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Workflow: `.github/workflows/android.yml`

It runs on push, pull request, and manual dispatch and uploads `app-debug.apk` as an artifact.
