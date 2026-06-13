# Flashing the Bike Speed Sensor

How to build and flash the firmware in `speed/speed.ino` onto a Seeed XIAO ESP32-C6
using [`arduino-cli`](https://arduino.github.io/arduino-cli/).

## Prerequisites

- **arduino-cli** installed and on your `PATH`.
- The **ESP32 board package** (`esp32:esp32`), which provides the Arduino core,
  compiler toolchain, and `esptool` uploader. Version 3.x or newer is required
  (the firmware uses the ESP32 task watchdog API).
- A **USB-C cable** that carries data (not charge-only).

Install the board package once:

```bash
arduino-cli core install esp32:esp32
```

## 1. Connect the board

Plug the XIAO ESP32-C6 into a USB port. The C6 exposes a native USB-Serial/JTAG
interface, so no separate USB-to-UART adapter or driver is needed.

Confirm the host sees it and find its port:

```bash
arduino-cli board list
```

Expected output (the port and board-name detail may vary):

```
Port         Protocol Type              Board Name          FQBN                      Core
/dev/ttyACM0 serial   Serial Port (USB) ESP32 Family Device esp32:esp32:esp32_family  esp32:esp32
```

Note the **port** — typically `/dev/ttyACM0` on Linux, `/dev/cu.usbmodemXXXX` on
macOS, or `COMx` on Windows. The commands below assume `/dev/ttyACM0`; substitute
your own.

### Linux / NixOS permissions

If you get a permission-denied error opening the port, either add your user to the
`dialout` group (`sudo usermod -aG dialout $USER`, then log out/in), or on NixOS add
a udev rule so the device is world-accessible:

```nix
services.udev.extraRules = ''
  SUBSYSTEMS=="usb", ATTRS{idVendor}=="303a", ATTRS{idProduct}=="1001", MODE="0666"
'';
```

(`303a:1001` is Espressif's USB-Serial/JTAG vendor/product ID.)

## 2. Compile

Run from the repository root (the directory containing `speed/`):

```bash
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 speed/
```

A successful build ends with a flash/RAM usage summary, e.g.:

```
Sketch uses 690114 bytes (52%) of program storage space. Maximum is 1310720 bytes.
Global variables use 18696 bytes (5%) of dynamic memory ...
```

## 3. Flash

```bash
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:XIAO_ESP32C6 speed/
```

`esptool` connects, erases the affected flash regions, writes the firmware,
verifies the hash, and hard-resets the chip into the new code. The tail of a
successful run looks like:

```
Verifying written data...
Hash of data verified.
Hard resetting via RTS pin...
```

> **Tip:** `compile` and `upload` can be combined — `arduino-cli compile --upload
> -p /dev/ttyACM0 --fqbn esp32:esp32:XIAO_ESP32C6 speed/`.

### If the upload can't connect

The C6's USB-Serial/JTAG bootloader is usually entered automatically. If a flash
fails to start, force download mode manually: hold **BOOT**, tap **RESET**, then
release **BOOT**, and re-run the upload.

## 4. Verify it's running

Open the serial monitor at **115200 baud** to watch the boot banner and health output:

```bash
arduino-cli monitor -p /dev/ttyACM0 -c baudrate=115200
```

```
=== Bike Speed booting ===
reset reason: power-on
build:        Jun  7 2026 12:40:11
advertising as: Bike Speed 3F9A
[health] up=5s revs=0 rate=0.0/s drops=0 hwm=0/31 notif=0 conn=0 disc=0 heap=212044
```

The device advertises over BLE as `Bike Speed XXXX`, where `XXXX` is the last two
bytes of its MAC. The onboard LED blinks at ~1 Hz while advertising and is solid
when a client is connected.

## Quick reference

```bash
# one-time
arduino-cli core install esp32:esp32

# each flash
arduino-cli board list                                                  # find the port
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 speed/             # build
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:XIAO_ESP32C6 speed/   # flash
arduino-cli monitor -p /dev/ttyACM0 -c baudrate=115200                 # watch output
```

See [`README.md`](README.md) for wiring, the BLE/CSC protocol, and firmware
configuration options.
