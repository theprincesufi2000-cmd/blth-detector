# Bluetooth Inspector Pro

Professional Android Bluetooth inspection utility for investigating Bluetooth Classic and BLE devices.

## What it inspects

- Local Bluetooth adapter state and capabilities
- Bonded/paired Classic Bluetooth devices
- Classic SDP UUID discovery
- BLE scan results
- RSSI, TX power and connectability when exposed by Android
- Advertising flags, service UUIDs, service data and manufacturer data
- Raw advertising bytes
- BLE GATT connection and service discovery
- Services, characteristics, properties, permissions and descriptors
- Characteristic reads
- Notifications/indications
- HEX writes to writable characteristics for controlled protocol research

> **Safety:** Do not write arbitrary bytes to an unknown characteristic on a production device. A write can change device state.

## Requirements

- Android Studio Ladybug or newer
- JDK 17
- Gradle 8.13 (GitHub Actions installs it automatically)
- Android SDK 36
- Android 8.0 (API 26) or newer device

## GitHub Actions

Every push, pull request, or manual workflow run builds `app-debug.apk` and uploads it as a workflow artifact.

The repository intentionally does not require a committed Gradle wrapper: GitHub Actions provisions Gradle 8.13, and Android Studio can import the project directly.

## Build locally

If Gradle 8.13 is installed:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Bluetooth permissions

Android 12+ uses `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. The app requests them at runtime. Location permission is not requested because the scanner declares that it does not derive the user's physical location from scan results.

## Project structure

```text
BluetoothInspectorPro/
├── app/
├── .github/workflows/android.yml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```
