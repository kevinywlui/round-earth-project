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
fusion, persistence — lives in the app. (The sensor *does* now keep a small
per-minute revolution **backlog** in flash so the app can recover what it missed
while disconnected — but that is still raw revolution counts, not computed
distance; see *Per-minute robustness*.)

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
   heading at record time but does *not* itself compute a displacement; that
   leaves the full 2-D displacement vector — north `Σ Δrev · circ · cos θ` and
   east `Σ Δrev · circ · sin θ`, where θ is the *true* (declination-corrected)
   heading — reconstructable offline by a consumer. (The original design exposed
   only the *northward* component; the model is now the complete N/E vector — see
   *2-D displacement reconstruction* below.) The sensor has no idea the earth is
   round.

## The lossless data model

Every wheel revolution is persisted as one raw `RevolutionEvent` row: the
cumulative count, the reboot-safe per-event delta, the sensor event time, the
monotonic accumulated time, the circumference *in effect at that moment*, and
both the magnetic and the declination-corrected true heading (each `NULL` when
unknown). **The per-revolution stream is never pre-aggregated** — speed, distance,
and the displacement vector are all *derived at read time*. (The per-minute backlog
and heading timelines added later are a deliberately separate, coarse recovery path
for the windows this lossless stream can't cover — see *Per-minute robustness* — not
a pre-aggregation of it.) This is what lets a ride be re-analyzed offline, and it
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
and a `0` would silently corrupt the displacement reconstruction. See
[`docs/live-heading.md`](bike-computer-android/docs/live-heading.md) and
[`docs/true-north.md`](bike-computer-android/docs/true-north.md).

Alongside the per-revolution stamp there is now a **per-minute heading timeline**
(`HeadingLogger` → `heading_minutes`): a phone-side ticker samples the compass
every ~2 s and writes one row per wall-clock minute — a **circular** (vector) mean
of the magnetic samples (an arithmetic mean of 350° and 10° would point *south*),
stored as both magnetic and true so declination stays recoverable per row, with a
`compassAccuracy` column. This timeline is the direction half of the 2-D
reconstruction for windows the live per-revolution stamp can't cover, and it is
sampled **whenever the phone is collecting, independent of BLE** — see *Per-minute
robustness* below.

## Per-minute robustness: the firmware backlog and the heading timeline

Two independent per-minute timelines close the two ways a ride can lose data
across a disconnect. They are a deliberate *evolution* of the original
"firmware stores nothing / no pre-aggregation" stance (see *Deliberate
non-goals*), confined to a coarse, lossy, **disjoint** recovery path that never
touches the lossless per-revolution model.

> **Status:** the app side is unit-tested (97 JVM tests). The firmware backlog is
> **review- and compile-validated only — not yet hardware-tested** on the
> XIAO ESP32-C6; the NVS-commit latency under BLE load, the IRAM-safety of an
> edge landing during a flash write, and the ring sizing against the stock 20 KB
> `nvs` partition are all flagged in `speed.ino` as hardware-validation items.

**The firmware backlog (revolutions survive a BLE outage).** The live CSC stream
only delivers revolutions while the app is *connected*; anything ridden while
disconnected — or across a sensor reboot — is gone. The firmware now keeps a
small on-device backlog so the app can recover it:

- Every minute, *if the cumulative count advanced*, the firmware appends a
  16-byte record `(boot_id, record_index, uptime_s, cumulative_revs)` to a
  180-slot ring (~3 h of riding-minutes; idle minutes are skipped, so no wear and
  no noise). The ring lives in RAM and is persisted as **one NVS blob** — far less
  flash entry/GC churn than per-slot keys, it fits the stock 20 KB `nvs`
  partition, and replay streams from RAM with no flash reads. It survives a full
  power loss.
- `boot_id` is an NVS-persisted monotonic counter, but the *next* value is
  committed only after ~5 s of stable uptime, so a brownout / power-bank reboot
  *loop* (which dies before that) reuses one `boot_id` and can't burn NVS wear on
  a failing supply. `record_index` is the global, reboot-surviving write counter,
  so `(sensorMac, record_index)` is unique forever even if a sub-stable boot
  reuses a `boot_id` — the app dedups on exactly that key. `boot_id` exists only
  to detect the `cumulative_revs` *reset* a reboot causes (deltas are never taken
  across a boot boundary).
- A new **un-advertised "Backlog" GATT service** (only CSC is advertised, so a
  generic head unit never sees it) exposes an **Info** READ characteristic
  (`boot_id` + current uptime = the app's clock anchor, plus the ring's index
  range and an overflow count) and a **Data** NOTIFY characteristic that streams
  the whole ring on subscribe, oldest-first, then a `0xFFFFFFFF` terminator.
  There is no app→device write: the app *pulls* by subscribing, exactly like the
  NUS boot-summary replay, and because every reconnect re-streams the whole ring,
  the idempotent `INSERT OR IGNORE` keying makes a re-replay a no-op.
- Two hardening details ride along: the wheel ISR moved from `millis()` (which
  lives in flash and would fault if an edge landed while a backlog write had the
  cache disabled) to the IRAM-resident `esp_timer_get_time()`, and
  `attachInterrupt` was moved *before* the boot wiring self-test so revolutions
  during the ≤8 s boot window are counted instead of silently dropped by a unit
  that reboots mid-ride. Failed NVS writes are counted (`nvserr`) and surfaced on
  the serial/BLE health line, so the data-loss risk of a full/failing flash is
  observable rather than silent.

**The heading timeline (direction while the phone runs).** The compass is a
*phone* signal, so `HeadingLogger` records a per-minute heading whenever the phone
is collecting — even with no sensor connected (see *Heading* above). The two
timelines join by wall-clock minute to reconstruct displacement.

**The "dark gap" asymmetry is the key consequence.** A BLE outage is asymmetric:
backlog **revolutions survive** it (the sensor kept logging), but **per-minute
heading exists only while the phone was running** `HeadingLogger`. So a recovered
minute can have a known distance but an *unknown direction* — which is exactly the
`NULL`-heading case the reconstruction handles below, never collapsing it to north.

## 2-D displacement reconstruction

The system now reconstructs the full 2-D displacement vector — north–south **and**
east–west — not just the northward component. There are two **disjoint** views of
a ride, and a consumer picks exactly one per interval; they are **never summed**:

- **The per-revolution *fine* view** — the lossless `RevolutionEvent` rows, each
  already carrying a true heading captured at record time. This is the precise
  view for any window the app was *connected* for.
- **The per-minute *coarse* view** — `DisplacementReconstructor`, a pure
  (no-Android, unit-tested) function over the `backlog_minutes` × `heading_minutes`
  join. For each minute it takes `Δrev` (the forward difference of the cumulative
  counter *within one `boot_id`*; a negative step or a boot boundary is skipped —
  a reboot resets the counter), multiplies by the circumference for the
  per-minute distance, and projects it onto the minute's *true* heading:
  `north += dist·cos θ`, `east += dist·sin θ`. This is the recovery view for the
  windows the live stream missed.

These two are alternatives, not addends: the per-minute rows exist *because* the
per-revolution stream was absent, so summing them would double-count.

**Unknown direction is first-class.** A minute with revolutions but a `NULL`/`NaN`
heading (the dark-gap case) has a *known distance* but an *unknown direction*: its
north/east components are emitted as `null` (blank in the CSV) and its distance is
tallied separately as `unknownDirectionMeters`. It is **never** read as `cos(0)` =
north, nor as `0` — either would silently corrupt the vector. Distance is always
known (it comes from revolutions); direction is the thing that can be missing.

**GPS is an optional anchoring aid, exported separately.** A best-effort
`LocationLogger` records GPS fixes into a *separate* `gps_fixes` table when
`ACCESS_FINE_LOCATION` is granted (it no-ops otherwise — GPS is a correction aid,
not a requirement). Its purpose is to let a consumer estimate and subtract the
compass **heading bias** — a constant magnetometer offset is the *dominant* error
in 2-D dead-reckoning and is otherwise invisible — and to bound the absolute drift
that pure dead-reckoning accumulates. The reconstruction model **never depends on**
GPS; the per-minute `compassAccuracy` column is recorded for the same reason (so a
consumer can discount low-confidence minutes). Raw coordinates are sensitive, so
they are deliberately kept **out of the ride-telemetry export** and downloaded as
their own CSV.

**Three exports, by design.** The main per-revolution CSV is unchanged; a
**per-minute displacement CSV** carries the coarse view (`minute_epoch`,
`timestamp_ms`, `distance_m`, `north_m`, `east_m`, `true_heading_degrees`,
`heading_sample_count`, `compass_accuracy` — blank `north_m`/`east_m` = unknown
direction); and the **GPS CSV** is the separate location download. The consumer
contract for the displacement CSV and the backlog timeline lives in
[`docs/backlog-and-displacement.md`](bike-computer-android/docs/backlog-and-displacement.md).

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

Every *app-initiated* teardown (ending collection — the notification's **Stop** action or
the service being killed — adapter-off, switching sensors, losing the connect race) calls
`disconnect()` **before** `close()`. `BluetoothGatt.close()`
only releases the local client handle — it does not drop the ACL link — so a bare close
strands the sensor connected to nobody (the firmware keeps `conn=1`) until its supervision
timeout fires as a `status=8`, and the next collection start layers a duplicate link over the
stale one. The unsolicited-drop callback is the one exception: it runs after the link is
already down, so it closes directly. This ordering is pinned by `stop_disconnectsRadioLinkBeforeClosing`.
Note that *backgrounding is no longer a teardown trigger* — collection is owned by a
foreground service (see below), so pocketing the phone or locking the screen keeps the link up.

## Collection outlives the UI: a foreground service

A ride lasts hours; the screen does not. Collection therefore cannot be tied to UI
visibility, so it lives in a **foreground service** (`CollectionService`,
`connectedDevice` type) rather than the Activity or the process foreground. The service is
the seam for one specific reason: a backgrounded *app* is scan-throttled and its BLE
callbacks are starved or killed the moment it loses the foreground, but a backgrounded
*service* showing an ongoing notification is kept alive by the OS and is exempt from that
throttling — which is exactly the difference between "records while you glance at it" and
"records the whole ride with the phone in a pocket." `connectedDevice` is the foreground-
service type for a BLE peripheral (Android 14+ rejects `startForeground` without a declared
type) and is backed by the `BLUETOOTH_CONNECT` grant the app already holds; the service
promotes itself to foreground *before* touching the radio.

Ownership, not mechanism, is what moved. `BikeApplication` still hosts the collection
pipeline (`BikeRepository` + `CscBleDataSource` + Room) and the magnetometer; the service
merely drives their start/stop — `startCollection()` on the first start command,
`stopCollection()` in `onDestroy` — so the "smart collector / dumb sensor" split is
untouched and this is purely an app-side change. `MainActivity` no longer starts the
repository directly: once the BLE permissions are granted it `startForegroundService`s
`CollectionService`. The service is `START_STICKY` and `startCollection()` is idempotent
(`BikeRepository.start` no-ops a second recording job, `HeadingProvider.start` re-registers
the same listener harmlessly), so an OS kill-and-restart under memory pressure re-enters
cleanly and the session-resume window stitches the ride back together instead of zeroing the
odometer. `POST_NOTIFICATIONS` is requested best-effort and deliberately does **not** gate
collection: a denied grant only hides the ongoing notice, the service still runs.

**The magnetometer runs for the entire ride — including backgrounded — on purpose.** It is
no longer gated on UI visibility, because a revolution recorded with the screen off must
still carry a *real* heading. This is the same invariant as "unknown heading is `NaN`/`NULL`,
never `0`" seen from the other side: were the compass torn down when the app backgrounded,
every background revolution would record an unknown heading (`NULL`), silently puncturing the
`Σ Δrev · circ · cos θ` northward reconstruction for the bulk of a real ride. Keeping the
sensor running is the small, continuous power cost that keeps per-revolution headings
non-`NULL` end to end.

## Deliberate non-goals

These are settled simplifications for a single-user app, not gaps:

- **One sensor, wheel data only.** No multi-sensor merging, no cadence/crank path.
- **No simulator / emulator path.** The app always talks to the real sensor.
- **Destructive migrations are the *fallback*, not the policy.** A purely
  *additive* schema change keeps a real migration so recorded history survives
  (the `7 → 8` bump that adds `backlog_minutes`, `heading_minutes`, and
  `gps_fixes` is hand-written and wipes nothing — durable history is the whole
  point of those tables). `fallbackToDestructiveMigration()` remains the last
  resort only for a future *incompatible* change, where dropping old rides for a
  single-user app is still acceptable. (This relaxes the earlier blanket
  "destructive migrations" non-goal, which no longer holds.)
- **No Battery Service.** The sensor runs off a USB power bank, whose charge the
  ESP32 cannot measure, so reporting a battery level would be a lie.
- **Miles by default** (the unit toggle remains).
