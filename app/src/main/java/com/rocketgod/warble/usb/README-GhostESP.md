# Ghost ESP board — serial capability reference

Factual inventory of what the connected Ghost ESP board exposes over its serial CLI. Captured directly
from the device (`help` output + probing), 2026-07-31. For review of what the board can do.

## Device identification
- Firmware: **Ghost ESP** (banner: `Ghost ESP Commands:`).
- MCU / transport: **ESP32 with native USB** (USB-Serial-JTAG). `VID:PID = 303A:1001`, USB CDC-ACM.
- Container serial / MAC: `F0:F5:BD:26:12:F8`.
- Serial: 115200 8N1. Commands are line-terminated (`\r\n`). Output is ESP-IDF logging — ANSI colour
  codes plus `I (<ms>) <TAG>: <text>` line prefixes (e.g. `WiFiManager`, `AP_MANAGER`).
- Opening the native-USB port does **not** reset the MCU (unlike a CP210x board).

## Full command list (verbatim from `help`)

| Command | Description | Usage / arguments |
|---|---|---|
| `help` | Display this help message. | `help` |
| `scanap` | Start a Wi-Fi access point (AP) scan. | `scanap` |
| `scansta` | Start scanning for Wi-Fi stations. | `scansta` |
| `stopscan` | Stop any ongoing Wi-Fi scan. | `stopscan` |
| `attack` | Launch an attack (e.g., deauthentication attack). | `attack -d`  (`-d` start deauth) |
| `list` | List Wi-Fi scan results or connected stations. | `list -a` (APs) / `list -s` (stations) |
| `beaconspam` | Start beacon spam with different modes. | `beaconspam [-r random / -rr Rickroll / -l AP list / [SSID]]` |
| `stopspam` | Stop ongoing beacon spam. | `stopspam` |
| `stopdeauth` | Stop ongoing deauthentication attack. | `stopdeauth` |
| `select` | Select an access point by index from the scan results. | `select -a <number>` |
| `startportal` | Start a portal with specified SSID and password. | `startportal <URL> <SSID> <Password> <AP_ssid> <Domain>`  — or offline: `startportal <FilePath> <AP_ssid> <Domain>` |
| `stopportal` | Stop Evil Portal. | `stopportal` |
| `blescan` | Handle BLE scanning with various modes. | `blescan [-f Find-the-Flippers / -ds BLE spam detector / -a AirTag scanner / -r raw BLE packets / -s stop]` |
| `capture` | Start a WiFi Capture (requires SD card or Flipper). | `capture [-probe / -beacon / -deauth / -raw / -wps / -pwn / -stop]` |
| `connect` | Connects to a specific WiFi network. | `connect <SSID> <Password>` |
| `dialconnect` | Cast a random YouTube video on all smart TVs on the LAN (requires `connect` first). | `dialconnect` |
| `powerprinter` | Print custom text to a printer on the LAN (requires `connect` first). | `powerprinter <Printer IP> <Text> <FontSize> <alignment>` — alignment: CM / TL / TR / BR / BL |

## Observed output formats
- **`scanap`**: runs a ~5 second full 2.4 GHz sweep (ESP-IDF scanner covers all channels itself),
  prints IDF status lines while scanning, then stops. Does **not** stream AP rows live.
- **`list -a`** (after a scan) prints, one per line:
  `[<index>] SSID: <ssid>, BSSID: <mac>, RSSI: <dBm>, Company: <OUI vendor>`
  Example: `[0] SSID: umc, BSSID: 34:53:D2:C4:5D:E6, RSSI: -55, Company: Unknown`
  No channel/frequency field is included.
- **`blescan`**: accepts the modes above; no parseable result list was emitted over serial during probing
  (results appear device-side; `list` reports `Usage: list -a (for Wi-Fi scan results)` only).
- **`capture`**: writes packet captures; requires an SD card or a Flipper host.
- No `led` / `rgb` serial command exists (`led`, `rgb`, `neopixel`, `rainbow` → `Unknown command`); the
  board's RGB LEDs are driven internally by the firmware per mode.
- No `wardrive`, `channel`, or `gps` command exists.

## What the Wardrive Go integration uses
`usb/GhostEsp.kt` + `usb/CdcAcm.kt`: opens the native-USB CDC-ACM port, handshakes (`help` → confirms the
`Ghost ESP` banner, since `303A:1001` is a generic native-USB ESP32 id), then loops `scanap` → `list -a`,
parses the AP rows above (SSID / BSSID / RSSI / vendor→maker), and feeds them into the standard
observation pipeline. Location comes from the phone's GPS. Only these read/scan commands are issued.
