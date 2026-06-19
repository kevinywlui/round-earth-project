# Bike Speed Sensor

A BLE wheel speed sensor built on the Seeed XIAO ESP32-C6. Broadcasts wheel revolution data over Bluetooth using the official **Cycling Speed and Cadence (CSC) Profile 1.0.1** specification, making it compatible with any CSC-capable app or head unit (Wahoo, Garmin, Strava, etc.) — including the companion `bike-computer-android` app, which records the raw revolution events as time-series data.

## How It Works

A hall effect sensor is mounted on the bike frame/fork and a magnet is attached to a wheel spoke. Each time the magnet passes the sensor, the ESP32 records a timestamp and increments the cumulative wheel revolution count. This data is packaged into the CSC Measurement characteristic and broadcast over BLE. A client computes speed and distance from the revolution count and the wheel circumference.

## Parts

| Part | Notes |
|------|-------|
| Seeed XIAO ESP32-C6 | Any ESP32 with BLE and Arduino core 3.x / IDF 5 — the task-watchdog config API (`esp_task_wdt_config_t`) used here is IDF-5-specific, and the diagnostic relaxed-read reasoning assumes single-core atomicity |
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

A standard **Device Information Service** (`0x180A`) is also exposed, with three read-only string characteristics. The app reads only the firmware revision, best-effort, after it has subscribed to the measurement notifications; firmware that predates this service is skipped silently.

| | |
|---|---|
| **Service UUID** | `0x180A` — Device Information |
| **Manufacturer Name** | `0x2A29` — `round-earth-project` |
| **Model Number** | `0x2A24` — `Bike Speed (XIAO ESP32-C6)` |
| **Firmware Revision** | `0x2A26` — `FW_VERSION` plus the compile-time build date, e.g. `1.0 (build Jun  7 2026)` |

No **Battery Service** (`0x180F`) is provided on purpose: the sensor runs off a USB power bank whose charge the ESP32 cannot measure.

### Backlog service (custom — recovers revolutions ridden while disconnected)

> **Status:** review- and compile-validated only — **not yet hardware-tested**. The NVS-commit latency under BLE load, the IRAM-safety of a wheel edge landing during a flash write, and the ring sizing against the stock 20 KB `nvs` partition are all flagged in `speed.ino` as hardware-validation items.

The live CSC stream only delivers revolutions while a client is *connected*. To let the companion app recover what it missed while disconnected — including across a sensor reboot or power loss — the firmware keeps a small **per-minute revolution backlog** in flash and exposes it over a custom, **un-advertised** GATT service (only CSC is advertised, so a generic head unit never sees it and interop is unaffected).

Every minute, *if the cumulative count advanced*, the firmware appends a 16-byte record `(boot_id, record_index, uptime_s, cumulative_revs)` to a 180-slot ring (~3 h of riding-minutes; idle minutes are skipped). The ring lives in RAM and is persisted as **one NVS blob**, so it survives a full power loss and fits the stock 20 KB `nvs` partition. `boot_id` is an NVS-persisted monotonic counter whose *next* value is committed only after ~5 s of stable uptime (so a brownout / power-bank reboot loop can't burn NVS wear), and `record_index` is the global, reboot-surviving write counter, so `record_index` is unique forever and the app can dedup re-streamed records.

| | |
|---|---|
| **Service UUID** | `5245424C-…` — custom, un-advertised (discovered by service discovery, not advertising) |
| **Info characteristic** | `52454201-…` — READ; 20-byte block: `boot_id`, current uptime (the app's clock anchor), the ring's index range, and an overflow count |
| **Data characteristic** | `52454202-…` — NOTIFY; on subscribe, streams the ring as 16-byte records oldest-first, then a `record_index == 0xFFFFFFFF` terminator |

The app **pulls** by subscribing (no app→device writes) and back-computes each record's wall-clock from the Info-block anchor; because every reconnect re-streams the whole ring, ingestion is made idempotent app-side. See [`bike-computer-android/docs/backlog-and-displacement.md`](../bike-computer-android/docs/backlog-and-displacement.md) for the full consumer contract. Two related hardening changes ride along: the wheel ISR now uses the IRAM-resident `esp_timer_get_time()` instead of flash-resident `millis()` (so an edge can fire safely during a backlog flash write), and `attachInterrupt` runs *before* the boot wiring self-test so revolutions during the boot window are counted rather than dropped by a unit that reboots mid-ride.

## Configuration

All configuration is at the top of `speed/speed.ino`:

| Constant | Default | Description |
|----------|---------|-------------|
| `DEVICE_NAME` | `"Bike Speed"` | BLE name prefix; the last two bytes of the MAC are appended at boot (e.g. `Bike Speed 3F9A`) so multiple units are distinguishable |
| `FW_VERSION` | `"1.0"` | Firmware version string reported (with the build date) over the Device Information Service `0x2A26` |
| `SENSOR_PIN` | `D0` | GPIO pin connected to the hall effect sensor |
| `MIN_MS` | `60` | Minimum milliseconds between triggers (debounce; ~125 km/h ceiling on a 2.1 m wheel) |
| `SELFTEST_WINDOW_MS` | `8000` | Boot wiring self-test window: how long to wait for a magnet pass to confirm the hall sensor's GND/V/OUT wiring (LED flashes 3x on success). `0` skips the wait |
| `WDT_TIMEOUT_S` | `5` | Task-watchdog timeout; reboots if `loop()` stalls (e.g. a wedged BLE stack) |
| `HEALTH_INTERVAL_MS` | `5000` | How often the serial health line is printed |
| `DEBUG_VERBOSE` | `0` | `0` (field default) prints only the health line and lifecycle events; `1` (bench bring-up) also logs every revolution |

## Diagnostics

Each revolution is captured by the falling-edge ISR into a small lock-free ring buffer
and drained in `loop()`, so a burst of revolutions between passes is never coalesced into
a single packet. A task watchdog reboots the sensor if `loop()` hangs, and the boot banner
reports the **reset reason** so a watchdog reboot or crash is visible after the fact.

Connect at 115200 baud to watch the serial output:

```
=== Bike Speed booting ===
reset reason: power-on
prev run:     RTC RAM cleared — VDD was fully removed (unplug / power-bank auto-shutoff), or a deep brownout
build:        <compile date/time>
advertising as: Bike Speed 3F9A
[alive] up=1s conn=0 heap=213120   # 1 Hz heartbeat for the first 5 s (see "Power / boot diagnostics")
[event] client connected
[rev] revs=1 t=1043   # only with DEBUG_VERBOSE=1; omitted at the field default (0)
[health] up=5s revs=12 rate=2.4/s drops=0 drej=0 hwm=2/31 notif=12 conn=1 disc=0 boot=1 log=0 ovf=0 nvserr=0 heap=212044
```

Health fields: `up` uptime (s), `revs` cumulative revolutions, `rate` revolutions/s over
the last interval, `drops` ring-buffer overflows, `drej` edges rejected by the `MIN_MS`
debounce (instrumentation: a persistently rising `drej` on a fast wheel would reveal real
revolutions being undercounted as contact bounce), `hwm` peak *observed* ring-buffer
occupancy vs. capacity (an upper bound — the ISR samples occupancy against a tail the
consumer may have already advanced, so it can only over-report how close you got to the
cliff, never under-report), `notif` CSC packets sent, `conn` link state, `disc`
disconnects since boot, `boot` the current backlog `boot_id`, `log` the backlog write
counter (next `record_index`), `ovf` backlog records overwritten before the app drained
them, `nvserr` failed backlog NVS writes (a non-zero value means the persisted cursor may
lag — a data-loss risk, surfaced rather than hidden), `heap` free bytes.

**On `drops`:** distance and the cumulative count are always correct (the ISR increments
`wheelRevolutions` before enqueuing, so an overflow only loses a *timestamp*, not a count).
But the companion app's per-revolution time-series is end-to-end lossless **only while
`drops == 0`** (i.e. `hwm` stays below capacity). A non-zero `drops` means individual
revolution event-times were coalesced under sustained `loop()` starvation, even though the
ride's total distance is preserved. In practice `drops` should stay 0; a non-zero value is
a signal to investigate what stalled the loop.

The onboard LED also shows link state: **solid** when a client is connected, **~1 Hz blink**
while advertising.

### Power / boot diagnostics

If the sensor **"shuts off" or resets after a few seconds**, the firmware logs enough to tell
which of three causes it is — the same lines stream over BLE (the `[boot]`/`[alive]` log
characteristic), so you can read them in the app without a serial console:

- **`reset reason:`** — the cause of the *last* reset, with a remedy hint appended for the
  faulty ones (`brownout`, `task watchdog`, `panic/exception`).
- **`prev run:`** — derived from a marker kept in the always-on **RTC RAM**, which survives a
  watchdog reset (and a shallow brownout) but is *lost when VDD is fully removed* — an unplug, a
  power bank that auto-shut-off, or a deep brownout that collapses VDD toward 0. So "RTC RAM
  cleared" means power was actually cut; "survived Ns" means the chip reset itself while still
  powered. (The marker is trusted only when the reset reason isn't itself a power-on, so a fast
  re-plug can't fake a "retained".)
- **`[alive] up=Ns`** — a 1 Hz heartbeat for the first 5 s. A unit that dies at ~3 s prints
  `up=1s`, `up=2s`, `up=3s`, then nothing, so you can *see* how long it ran before it died (the
  first full `[health]` line otherwise only appears at 5 s).

Putting it together:

| reset reason | `prev run` | likely cause | fix |
|---|---|---|---|
| `power-on` | RTC RAM cleared | **power bank auto-shutoff** (low draw) or a loose plug | wall/PC USB, or a bank with a low-current "trickle" mode |
| `brownout` | (either) | supply sag — thin/long cable, tired bank, or **missing U.FL antenna** | better cable/supply; confirm the antenna is attached |
| `task watchdog` | survived Ns | `loop()` stalled > `WDT_TIMEOUT_S` (e.g. a wedged BLE stack) | investigate what blocked the loop |

A power-bank-powered, BLE-only sensor draws only tens of mA, **below the keep-alive threshold of
many power banks** — the most common "it just turns off after a few seconds" cause, and exactly
what the `power-on` + "RTC RAM cleared" combination confirms.

## Wiring self-test

At power-on the firmware runs a quick wiring self-test. (The wheel ISR is now armed *before*
this self-test — not after — so revolutions ridden during the up-to-8 s boot window are
counted instead of silently dropped by a unit that reboots mid-ride; the self-test polls the
pin directly, so its only side effect is that the confirming magnet wave may register a rev or
two.) During an `SELFTEST_WINDOW_MS` window (8 s by default) the LED **winks rapidly** — pass
the magnet by the sensor once during this window and, if the sensor responds, the LED
**flashes three times** to confirm the wiring.

Why a magnet pass is the test: the A3144 is open-collector and active low, so an idle
(untriggered) sensor and a *disconnected* `OUT` pin both read HIGH through the MCU's pull-up —
indistinguishable. Only a sensor that is powered (**V** and **GND** correct) *and* whose
`OUT` actually reaches **D0** (signal correct) can sink the line LOW, so a debounced
HIGH→sustained-LOW transition confirms all three wires at once. The self-test first establishes
the idle-HIGH baseline, then watches for that transition. The serial log shows the result:

```
[selftest] line idle HIGH; pass a magnet within 8s to confirm wiring...
[selftest] PASS: magnet detected — GND, V and OUT all wired correctly
```

A momentary low at boot is **not** treated as a fault: a wheel parked with the magnet next to
the sensor holds a correctly-wired output low until it moves, so the test waits for the line to
clear to HIGH first. Only a line that **never clears** for the whole window
(`[selftest] FAIL: signal stuck LOW`) is reported as a fault — `OUT` shorted to GND, or a jammed
sensor. If no magnet is waved within the window the test is **inconclusive** (not a failure) and
the sensor boots normally — so a bike-mounted unit on a power bank isn't blocked just because
nobody waved a magnet at it.

Because the self-test runs before any client can connect, its verdict is also **replayed over
BLE** in the `[boot]` summary the moment the app subscribes to the log characteristic — so the
wiring result is visible in the app, not just on a serial console:

```
[selftest] wiring PASS — magnet seen: GND, V and OUT all wired correctly
[selftest] wiring inconclusive — no magnet waved at boot (wave one within the window and reboot to confirm)
[selftest] wiring FAIL — signal stuck LOW (OUT shorted to GND, or sensor jammed on)
```
