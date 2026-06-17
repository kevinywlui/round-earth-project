# Architecture

How the bike computer is put together, and *why* — the design decisions that the
code embodies but that aren't obvious from any single file. For the wire format
and per-component setup, see the [top-level README](README.md), the
[firmware README](bike-speedometer/README.md), and the focused design notes under
[`bike-computer-android/docs/`](bike-computer-android/docs/).

## The one idea: a dumb sensor and a smart app, joined by a standard seam

The system is two programs connected by exactly one interface: the **standard BLE
Cycling Speed & Cadence (CSC) profile**. Every other design choice falls out of
that.

```
  FIRMWARE (ESP32-C6)            │  BLE / CSC 0x1816  │        ANDROID APP
  "dumb sensor"                  │                    │        "smart collector"
                                 │                    │
  magnet → hall ISR              │                    │   scan → connect → subscribe
   ├ cumulative wheel revs (u32) │                    │   read Feature (wheel?) + DIS (fw)
   └ event time (1/1024 s, u16)  │  ── notify  ────►  │   CscMeasurementDecoder
     → lock-free ring buffer     │   (7-byte packet)  │     Δrevs, Δticks →
     → loop() emits 1/rev  ──────┼────────────────────┼──►  speed · distance · monotonic sensor time
                                 │                    │   + wheel circumference
  watchdog · re-advertise · DIS  │                    │   + phone compass heading + declination
                                 │                    │        ↓
                                 │                    │   RevolutionEvent rows (Room, lossless)
                                 │                    │   speed / distance / heading at read time
```

**The sensor computes nothing.** It only ever reports a cumulative revolution
count and a 16-bit event time. It does not know the wheel circumference, so it
*cannot* know speed or distance. All of that — units, circumference, heading
fusion, persistence — lives in the app.

Three things follow:

1. **Interoperability.** The firmware is a *standard* CSC sensor and the app is a
   *standard* CSC collector, so either side is replaceable: the sensor works with
   any cycling head unit, the app with any CSC sensor. The contract is just the
   7-byte packet.
2. **All policy is soft.** Circumference, units, heading fusion, declination — the
   things you change — live app-side, where they're a settings toggle, never a
   reflash of the device bolted to the bike. The hard-to-change half stays trivial
   and robust.
3. **"Round earth" is an app-side fusion.** The sensor measures *distance*; the
   phone's compass measures *direction*. The app stamps each revolution with a
   heading at record time but does *not* itself compute a north component; that
   leaves northward distance — `Σ Δrev · circ · cos θ`, where θ is the *true*
   (declination-corrected) heading — reconstructable offline by a consumer. The
   sensor has no idea the earth is round.

## The lossless data model

Every wheel revolution is persisted as one raw `RevolutionEvent` row: the
cumulative count, the reboot-safe per-event delta, the sensor event time, the
monotonic accumulated time, the circumference *in effect at that moment*, and
both the magnetic and the declination-corrected true heading (each `NULL` when
unknown). **Nothing is pre-aggregated** — speed, distance, and northward travel are
all *derived at read time*. This is what lets a ride be re-analyzed offline, and it
means a circumference change only affects future rows — the raw per-revolution rows
survive the correction (they aren't rewritten) rather than being lost to a
pre-aggregated total. Storing both headings is the same idea applied to direction:
declination stays recoverable per-row (when both headings are present) as
`true − magnetic`, so a later declination
correction only changes a mutable preference and never rewrites recorded rides.

## The clock model (three clocks)

Timing is the subtlest part of the system because three different clocks are in
play:

| Clock | Source | Used for |
|---|---|---|
| **Sensor event time** | firmware, 1/1024 s, 16-bit (wraps every 64 s) | the precise, jitter-free inter-revolution timing the app unwraps |
| **Phone monotonic** | `elapsedRealtime` | wrap detection, reconnect backoff, estimating an ambiguous (reboot / >64 s) gap |
| **Phone wall-clock** | `currentTimeMillis` (epoch) | the stored row timestamp, session resume, and bridging across a reconnect |

The app unwraps the sensor event time into a **monotonic accumulator** so offline
speed reconstruction is free of BLE delivery jitter. The full consumer contract —
including the per-connection reset and which clock bridges which gap — is in
[`docs/offline-timing.md`](bike-computer-android/docs/offline-timing.md). The two
distinct "wrap" thresholds (a conservative 60 s guard that *suppresses* a possibly-
false speed, vs. the true 64 s event-time wrap that the *accumulator* uses) live in
`CscMeasurementDecoder`.

## Heading

Heading comes from the phone's rotation-vector sensor, corrected for how the phone
is mounted (a calibratable offset) and converted to true north with a declination
that's either entered by hand or auto-detected from a coarse location fix. The
load-bearing invariant: **unknown heading is `NaN` in memory and `NULL` in the
database — never `0` (due north)**, because SQLite coerces a bound `NaN` to `NULL`
and a `0` would silently corrupt the northward reconstruction. See
[`docs/live-heading.md`](bike-computer-android/docs/live-heading.md) and
[`docs/true-north.md`](bike-computer-android/docs/true-north.md).

## Concurrency model

- **Firmware:** a single-producer/single-consumer lock-free ring buffer hands
  ISR-captured `(revs, time)` events to `loop()` with acquire/release ordering, so
  a fast wheel can't lose per-revolution timestamps; a task watchdog reboots a
  wedged BLE stack.
- **App:** GATT callbacks arrive on binder threads. The single live connection is
  an `AtomicReference<SensorConnection?>` mutated by CAS (claim the empty slot,
  `CAS null→conn`, before `connectGatt`; on teardown evict only if still mine,
  `CAS this→null`), the discovered-sensor map is mutated
  atomically via `compute`/`computeIfPresent`, the merged odometer is guarded by a
  lock, and the live dashboard state is an atomic `MutableStateFlow.update`. The
  decoder is single-threaded by contract — only ever entered from its one
  connection's serialized callback thread.

## BLE resilience

Connecting once is easy; staying connected is the work. The app uses an exponential
**`ReconnectPolicy`** to throttle status-133 flaps and null `connectGatt` results,
clears the penalty once a sensor is confirmed *subscribed* (so a healthy drop
reconnects fast), recovers from a Bluetooth adapter toggle, and only reports
`CONNECTED` after the notification subscription is confirmed — never on a bare GATT
link. The reconnect math is unit-tested in `ReconnectPolicyTest`.

Every *app-initiated* teardown (backgrounding, adapter-off, switching sensors, losing
the connect race) calls `disconnect()` **before** `close()`. `BluetoothGatt.close()`
only releases the local client handle — it does not drop the ACL link — so a bare close
strands the sensor connected to nobody (the firmware keeps `conn=1`) until its supervision
timeout fires as a `status=8`, and the next foreground layers a duplicate link over the
stale one. The unsolicited-drop callback is the one exception: it runs after the link is
already down, so it closes directly. This ordering is pinned by `stop_disconnectsRadioLinkBeforeClosing`.

## Deliberate non-goals

These are settled simplifications for a single-user app, not gaps:

- **One sensor, wheel data only.** No multi-sensor merging, no cadence/crank path.
- **No simulator / emulator path.** The app always talks to the real sensor.
- **Destructive database migrations.** A schema change recreates the table and
  drops old rides on purpose — there is no migration code to maintain.
- **No Battery Service.** The sensor runs off a USB power bank, whose charge the
  ESP32 cannot measure, so reporting a battery level would be a lie.
- **Miles by default** (the unit toggle remains).
