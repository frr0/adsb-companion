# Heltec ADS-B Companion

Android companion application for the [Heltec-v3_ADS-B_Meshcore-dualboot](https://github.com/frr0/Heltec-v3_ADS-B_Meshcore-dualboot) firmware running on Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262) boards.

It connects over Bluetooth Low Energy (Nordic UART Service) to display live ADS-B flight telemetry, mirror the board's OLED screen, control radio tuning and hardware buttons remotely, and manage flash logs.

## Features

- **BLE Connection**: Scans and connects to `Heltec-ADSB` devices over Nordic UART Service (NUS).
- **Live Flight Telemetry & OLED Mirror**: Displays active callsign, barometric altitude, ground speed, coordinates, RF packet counters, and a live mirror of the Heltec V3 OLED display.
- **Remote Hardware Control**:
  - Frequency tuning for the SX1262 radio (`-100 kHz`, `+100 kHz`, default `1089.8 MHz`, and persistent flash save).
  - Emulation of the physical PRG button (`Tap`, `Hold`, `Double Click`).
  - Aircraft history navigation (`Previous`, `Live`, `Clear History`) and hardware diagnostics (`status`).
- **Flash Logs & Export**: Live log streaming (disabled by default), on-demand flash memory dump (`dump`), and export to `Downloads/adsb_log_<timestamp>.txt`.

## Firmware

This app requires a Heltec V3 board flashed with the companion firmware:

- Repository: [https://github.com/frr0/Heltec-v3_ADS-B_Meshcore-dualboot](https://github.com/frr0/Heltec-v3_ADS-B_Meshcore-dualboot)

## Build

Requires Android SDK (API 34) and JDK 17.

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
