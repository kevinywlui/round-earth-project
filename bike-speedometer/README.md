# Bike Speed Sensor

A BLE wheel speed sensor built on the Seeed XIAO ESP32-C6. Broadcasts wheel revolution data over Bluetooth using the official **Cycling Speed and Cadence (CSC) Profile 1.0.1** specification, making it compatible with any CSC-capable app or head unit (Wahoo, Garmin, Strava, etc.) — including the companion `bike-computer-android` app, which records the raw revolution events as time-series data.

## How It Works

A hall effect sensor is mounted on the bike frame/fork and a magnet is attached to a wheel spoke. Each time the magnet passes the sensor, the ESP32 records a timestamp and increments the cumulative wheel revolution count. This data is packaged into the CSC Measurement characteristic and broadcast over BLE. A client computes speed and distance from the revolution count and the wheel circumference.

## Parts

| Part | Notes |
|------|-------|
| Seeed XIAO ESP32-C6 | Any ESP32 with BLE will work |
| A3144 hall effect sensor | Digital output, active low |
| Small neodymium magnet | Attached to a wheel spoke |

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
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 speed/
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:XIAO_ESP32C6 speed/
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
| **Feature characteristic** | `0x2A5C` — declares wheel revolution data supported |

The measurement packet is 7 bytes: a flags byte (`0x01`, wheel revolution data present), a 32-bit little-endian cumulative wheel revolution count, and a 16-bit little-endian "last wheel event time" in units of 1/1024 s.

## Configuration

All configuration is at the top of `speed/speed.ino`:

| Constant | Default | Description |
|----------|---------|-------------|
| `DEVICE_NAME` | `"Bike Speed"` | BLE name prefix; the last two bytes of the MAC are appended at boot (e.g. `Bike Speed 3F9A`) so multiple units are distinguishable |
| `SENSOR_PIN` | `D0` | GPIO pin connected to the hall effect sensor |
| `MIN_MS` | `60` | Minimum milliseconds between triggers (debounce; ~125 km/h ceiling on a 2.1 m wheel) |
| `WDT_TIMEOUT_S` | `5` | Task-watchdog timeout; reboots if `loop()` stalls (e.g. a wedged BLE stack) |
| `HEALTH_INTERVAL_MS` | `5000` | How often the serial health line is printed |
| `DEBUG_VERBOSE` | `1` | `1` logs every revolution; `0` prints only the health line and lifecycle events |

## Diagnostics

Each revolution is captured by the falling-edge ISR into a small lock-free ring buffer
and drained in `loop()`, so a burst of revolutions between passes is never coalesced into
a single packet. A task watchdog reboots the sensor if `loop()` hangs, and the boot banner
reports the **reset reason** so a watchdog reboot or crash is visible after the fact.

Connect at 115200 baud to watch the serial output:

```
=== Bike Speed booting ===
reset reason: power-on
build:        Jun  7 2026 12:40:11
advertising as: Bike Speed 3F9A
[event] client connected
[rev] revs=1 t=1043
[health] up=5s revs=12 rate=2.4/s drops=0 hwm=2/31 notif=12 conn=1 disc=0 heap=212044
```

Health fields: `up` uptime (s), `revs` cumulative revolutions, `rate` revolutions/s over
the last interval, `drops` ring-buffer overflows (distance is still correct — only an
individual timestamp is lost), `hwm` peak ring-buffer occupancy vs. capacity, `notif` CSC
packets sent, `conn` link state, `disc` disconnects since boot, `heap` free bytes.

The onboard LED also shows link state: **solid** when a client is connected, **~1 Hz blink**
while advertising.
