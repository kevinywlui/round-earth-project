# round-earth-project

A homemade bike computer in two halves: a DIY **wheel-speed sensor** and the
**Android app** that consumes it.

- **[`bike-speedometer/`](bike-speedometer/README.md)** — ESP32-C6 Arduino
  firmware. A hall-effect sensor counts wheel revolutions and broadcasts them
  over BLE using the standard Cycling Speed & Cadence (CSC) profile
  (wheel-revolution data only). Compatible with any CSC head unit, not just this
  app.
- **[`bike-computer-android/`](bike-computer-android/)** — Kotlin + Jetpack
  Compose app. Connects to one CSC sensor, records a **lossless** per-revolution
  time-series in Room, and shows speed, odometer, and a magnetic + true-north
  compass. It also recovers revolutions ridden while disconnected from the
  sensor's on-device **backlog**, keeps a per-minute heading timeline, and
  reconstructs a 2-D (north/east) **displacement**. See its
  [`docs/`](bike-computer-android/docs/) for the heading, true-north, offline-timing,
  and backlog/displacement design notes.

For the design as a whole — the dumb-sensor / smart-app split, the lossless data
model, the three-clock timing model, and the concurrency and BLE-resilience
choices — see **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## End-to-end data flow

```
magnet passes hall sensor
  → ISR timestamps the revolution (lock-free ring buffer, firmware)
  → CSC Measurement notify over BLE (cumulative revs + 1/1024 s event time)
  → app decodes the delta (CscMeasurementDecoder)
  → raw per-revolution event stored in Room (lossless)
  → speed / distance / heading derived at read time
```

The sensor only ever reports the cumulative wheel-revolution count and a 16-bit
event time; **all** speed and distance math happens app-side from those plus the
configured wheel circumference. Storing the raw events (not pre-aggregated
speeds) keeps rides re-analyzable later.

## The shared CSC contract

| | |
|---|---|
| Service | `0x1816` (Cycling Speed and Cadence) |
| Measurement characteristic | `0x2A5B`, notify |
| Feature characteristic | `0x2A5C`, read; 16-bit LE, bit 0 = wheel-revolution data supported |
| Packet | 7 bytes: flags `0x01`, uint32 LE cumulative wheel revs, uint16 LE event time (1/1024 s, wraps at 64 s) |

After subscribing, the app reads the Feature characteristic to confirm bit 0 is
set; if it is explicitly clear (a non-wheel CSC sensor that can't drive speed),
the picker shows a "no wheel-speed data" warning. An unread/failed read stays
silent.

The firmware also exposes a standard **Device Information Service** (`0x180A`):
Manufacturer `0x2A29`, Model `0x2A24`, and Firmware Revision `0x2A26` (the
`FW_VERSION` plus the build date). After subscribing, the app reads only the
firmware revision, best-effort, and surfaces it under the sensor in the picker;
firmware without a DIS is skipped silently. No Battery Service is provided — the
sensor runs off a USB power bank whose charge the ESP32 cannot measure.

## Out of scope

`sunsight/` is an unrelated web utility (it computes *solar* declination, which
is a different quantity from the *magnetic* declination the compass uses) and is
not part of the bike computer.
