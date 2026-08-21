# Protocol Discovery Notebook — Bao Biao

## Evidence currently available

The supplied Bluetooth Inspector Pro screenshots show:

- Device name: `Bao Biao`
- GATT address: `68:95:75:03:2C:19`
- GATT state: `CONNECTED`
- Services discovered: `7`
- Custom primary service: `00001000-0000-1000-8000-00805f9b34fb`
- Candidate TX/control characteristic: `00001001-0000-1000-8000-00805f9b34fb`
  - Properties: `READ, WRITE, WRITE_NO_RESPONSE`
- Candidate RX/event characteristic: `00001002-0000-1000-8000-00805f9b34fb`
  - Properties: `NOTIFY`
- HID service: `00001812-0000-1000-8000-00805f9b34fb`
- Battery service: `0000180f-0000-1000-8000-00805f9b34fb`

These facts establish a usable BLE transport. They do **not** establish the private command grammar.

## High-confidence discovery procedure

1. Connect to the same physical device.
2. Subscribe to `00001002` before sending a test command.
3. Record the idle notification stream for at least 10 seconds.
4. Send exactly one known-safe command at a time through `00001001`.
5. Record:
   - exact TX bytes
   - write type
   - GATT write status
   - every RX notification byte sequence and timestamp
   - any visible device-state change
6. Repeat the same command to check determinism.
7. Change one byte only and repeat.
8. Compare the RX and physical result with the baseline.
9. Only after a pattern is repeatable should a semantic label be assigned.

## Why this is more accurate than guessing

A successful `WRITE` callback only proves that Android/the GATT server accepted the transport operation. It does not prove that the bytes represented “power on”, “mode 2”, “speed 5”, etc.

The app therefore treats the following as separate evidence levels:

- **Transport-confirmed:** GATT write accepted.
- **Response-confirmed:** a reproducible read/notification follows the write.
- **Behavior-confirmed:** the physical/device state changes reproducibly.
- **Semantically confirmed:** the meaning is backed by vendor documentation or a repeatable controlled experiment.

## Probe mode

Protocol Lab can build at most 32 candidates from a seed command. It mutates one byte at a time using the boundary/value set:

`00 01 02 7F 80 FF`

The probe is deliberately limited and stoppable. Do not run a blind probe against equipment where an unknown write could cause unsafe motion, heating, activation, unlocking, or other consequential behavior.

## Command table template

| ID | TX characteristic | HEX command | Write type | RX characteristic | RX HEX | Device behavior | Confidence |
|---|---|---|---|---|---|---|---|
| C001 | 00001001 | | WRITE / NO_RESPONSE | 00001002 | | | |
| C002 | 00001001 | | WRITE / NO_RESPONSE | 00001002 | | | |
| C003 | 00001001 | | WRITE / NO_RESPONSE | 00001002 | | | |

## Goal

Build a verified command dictionary from evidence, rather than claiming that every possible byte sequence is a valid command. No software-only GATT inspection can guarantee discovery of undocumented commands that the device never exposes, or commands hidden behind authentication/encryption.
