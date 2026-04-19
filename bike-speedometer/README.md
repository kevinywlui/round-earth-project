# Bike Cadence Sensor

A BLE cadence sensor built on the Seeed XIAO ESP32-C6. Broadcasts crank revolution data over Bluetooth using the official **Cycling Speed and Cadence (CSC) Profile 1.0.1** specification, making it compatible with any CSC-capable app or head unit (Wahoo, Garmin, Strava, etc.).

## How It Works

A hall effect sensor is mounted on the bike frame and a magnet is attached to the crank arm. Each time the magnet passes the sensor, the ESP32 records a timestamp and increments the cumulative revolution count. This data is packaged into the CSC Measurement characteristic and broadcast over BLE.

## Parts

| Part | Notes |
|------|-------|
| Seeed XIAO ESP32-C6 | Any ESP32 with BLE will work |
| A3144 hall effect sensor | Digital output, active low |
| Small neodymium magnet | Attached to crank arm |

## Wiring

```
A3144 Hall Effect Sensor  (flat face toward you: VCC | GND | OUT)
  VCC → 3.3V
  GND → GND
  OUT → D0
```

The ESP32's internal pull-up is used — no external resistor needed.

## Setup

Install the ESP32 board package and flash with `arduino-cli`:

```bash
arduino-cli core install esp32:esp32
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 cadence/
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:XIAO_ESP32C6 cadence/
```

On NixOS, add udev rules so the device is accessible without sudo:

```nix
services.udev.extraRules = ''
  SUBSYSTEMS=="usb", ATTRS{idVendor}=="303a", ATTRS{idProduct}=="1001", MODE="0666"
'';
```

## Bluetooth Specification

Implements the [Cycling Speed and Cadence Profile 1.0.1](https://www.bluetooth.com/specifications/specs/cycling-speed-and-cadence-profile-1-0-1/).

| | |
|---|---|
| **Service UUID** | `0x1816` — Cycling Speed and Cadence |
| **Measurement characteristic** | `0x2A5B` — notifies on each revolution with cumulative count and event timestamp |
| **Feature characteristic** | `0x2A5C` — declares crank revolution data supported |

## Configuration

All configuration is at the top of `cadence/cadence.ino`:

| Constant | Default | Description |
|----------|---------|-------------|
| `DEVICE_NAME` | `"Bike Cadence"` | BLE device name advertised to clients |
| `SENSOR_PIN` | `D0` | GPIO pin connected to the hall effect sensor |
| `MIN_MS` | `80` | Minimum milliseconds between triggers (debounce) |
