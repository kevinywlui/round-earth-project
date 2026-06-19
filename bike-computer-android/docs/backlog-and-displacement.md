# Backlog, per-minute heading, and 2-D displacement

How the firmware revolution **backlog**, the phone-side **per-minute heading**
timeline, and the **2-D displacement** reconstruction fit together — and the
consumer contract for the displacement CSV. This is the *coarse, recovery* path
for the windows the lossless per-revolution stream can't cover; it is **disjoint**
from that fine path and never summed with it. For the wider design rationale see
[ARCHITECTURE.md](../../ARCHITECTURE.md) → *Per-minute robustness* and *2-D
displacement reconstruction*; for the timing rules see
[offline-timing.md](offline-timing.md); for the heading log see
[live-heading.md](live-heading.md).

> **Status:** the app side (parser, reconstruction, migration, DAOs) is unit-tested
> on the JVM (97 tests). The **firmware backlog is review- and compile-validated
> only — not yet hardware-tested** on the XIAO ESP32-C6. The NVS-commit latency
> under BLE load, the IRAM-safety of a wheel edge landing during a flash write, and
> the ring sizing against the stock 20 KB `nvs` partition are flagged in `speed.ino`
> as hardware-validation items.

## The two timelines and why they're separate

A BLE outage loses data in two *asymmetric* ways, and each timeline closes one of
them:

| | Survives a BLE outage? | Survives a sensor reboot/power-loss? |
|---|---|---|
| **Backlog revolutions** (firmware → `backlog_minutes`) | **yes** — the sensor keeps logging while disconnected | **yes** — NVS-persisted; `boot_id` marks the reset |
| **Per-minute heading** (`HeadingLogger` → `heading_minutes`) | only while the **phone** was collecting | n/a (phone-side) |

The consequence — the **"dark gap"** — is that a recovered minute can have a known
*distance* (revolutions survived) but an *unknown direction* (no heading was logged
that minute). That unknown-direction case is first-class in the reconstruction
below; it is never silently read as north.

## The firmware backlog wire format

The sensor exposes an **un-advertised "Backlog" GATT service** (only CSC is
advertised, so a generic head unit never sees it). All multi-byte fields are
little-endian, matching the CSC packet packing.

- **Info characteristic** (READ, 20 bytes): `boot_id`, `current_uptime_s`,
  `oldest_index`, `newest_index`, `overflow`. Refreshed on every read, so
  `current_uptime_s` is the app's **clock anchor** at the moment it reads.
- **Data characteristic** (NOTIFY, 16-byte records): on subscribe, the firmware
  streams the whole ring oldest-first — `(boot_id, record_index, uptime_s,
  cumulative_revs)` per record — then a **terminator** with
  `record_index == 0xFFFFFFFF`. An all-zero slot (`boot_id == 0`) is an empty ring
  slot and is skipped.

The app **pulls** by subscribing — there is no app→device write. Because every
reconnect re-streams the *whole* ring, ingestion is idempotent: `INSERT OR IGNORE`
keyed on `(sensorMac, record_index)` makes a re-replay a no-op. `record_index` is
the sensor's global, reboot-surviving write counter (unique forever per sensor);
`boot_id` exists to detect the `cumulative_revs` reset a reboot causes. The ring is
180 slots (~3 h of riding-minutes; idle minutes aren't written); on overflow the
oldest record is overwritten and `overflow` is incremented so the loss is
observable rather than silent. Parsing lives in the pure, unit-tested
`BacklogRecordParser`.

## Wall-clock for backlog rows is ESTIMATED

Each `backlog_minutes.wallClockMillis` is **back-computed**, not measured, from the
single connect-time anchor (the Info block's `current_uptime_s` paired with
`System.currentTimeMillis()` at the instant of the read):

```
wallClock(record) = anchorWallClock − (anchorUptime − record.uptime_s) · 1000
```

- **Precise for the current boot** (`record.boot_id == info.boot_id`).
- **A lower bound for prior boots** — the sensor has **no RTC**, so the off-duration
  across a reboot is unknowable; those rows are pinned at the current boot's start.

Do **not** mix this estimated wall-clock with the live per-revolution
`cumulative_event_time_1024` series — they are different clocks at different
resolutions. See [offline-timing.md](offline-timing.md) for the full rule set. The
recovered *distance*, by contrast, keys on revolutions and is clock-independent.

## The 2-D reconstruction

`DisplacementReconstructor` (pure, no Android, unit-tested) joins `backlog_minutes`
with `heading_minutes` by wall-clock minute and produces the per-minute
displacement plus a running total:

- Group by `boot_id`, order by `record_index`. For each consecutive pair within a
  boot, `Δrev = cur.cumulativeRevolutions − prev.cumulativeRevolutions`; a
  non-positive step (a reboot boundary, duplicate, or no progress) is skipped.
  **Deltas are never taken across a boot boundary** (a reboot resets the counter).
- `distance = Δrev · circumference`. Distance is **always known**.
- Look up the minute's *true* heading θ. If it is present:
  `north += distance·cos θ`, `east += distance·sin θ`. If it is `NULL`/`NaN`
  (**unknown direction**): the minute's `north`/`east` are emitted as `null` and
  its distance is tallied into `unknownDirectionMeters` — **never** read as
  `cos(0)` = north, **never** as 0.

The per-minute *coarse* view here and the per-revolution *fine* view (the lossless
`RevolutionEvent` rows) are **alternatives**, not addends: a consumer picks exactly
one per interval. The per-minute rows exist *because* the per-revolution stream was
absent, so summing them would double-count.

## The displacement CSV (consumer contract)

`exportDisplacementCsvTo` writes one row per reconstructed minute:

| Column | Meaning |
|---|---|
| `minute_epoch` | wall-clock minute bucket = `floor(timestamp_ms / 60000)`; the join key |
| `timestamp_ms` | `minute_epoch · 60000` — **estimated** wall-clock (see above) |
| `distance_m` | recovered distance for the minute (`Δrev · circumference`); always known |
| `north_m` | north component `distance·cos θ`, or **blank** when direction is unknown |
| `east_m` | east component `distance·sin θ`, or **blank** when direction is unknown |
| `true_heading_degrees` | the minute's circular-mean true heading, or blank if unknown |
| `heading_sample_count` | valid compass samples folded into the minute (a confidence/weight signal) |
| `compass_accuracy` | worst `SENSOR_STATUS_ACCURACY_*` that minute; `-1` = unknown |

**Blank `north_m`/`east_m` mean unknown direction, not zero.** A consumer summing
the vector must skip those rows for the N/E total while still counting their
`distance_m` as distance-of-unknown-direction. The circumference stamped on each
backlog row is the value *in effect at ingest*; if it changed during a
disconnected window, that window's recovered distance uses the current value (the
sensor stores only revolutions) — a rare, acceptable approximation.

## GPS: an optional anchoring aid, exported separately

A best-effort `LocationLogger` records GPS fixes into a **separate** `gps_fixes`
table when `ACCESS_FINE_LOCATION` is granted (it no-ops otherwise — GPS is a
correction aid, not a requirement). Its purpose is to let a consumer estimate and
subtract the compass **heading bias** — a constant magnetometer offset is the
*dominant* error in 2-D dead-reckoning and is otherwise invisible — and to bound
the absolute drift pure dead-reckoning accumulates. **The reconstruction never
depends on GPS.** Raw coordinates are sensitive, so they are deliberately kept out
of the ride-telemetry exports and downloaded as their own CSV
(`id, timestamp_ms, latitude, longitude, accuracy_m, altitude_m, bearing_degrees,
speed_mps`).

## The three exports

| Export | Source | Notes |
|---|---|---|
| **Per-revolution CSV** (`exportCsvTo`) | `RevolutionEvent` (lossless fine view) | unchanged |
| **Per-minute displacement CSV** (`exportDisplacementCsvTo`) | `backlog_minutes` × `heading_minutes` (coarse view) | the columns above; disjoint from the fine view |
| **GPS CSV** (`exportGpsCsvTo`) | `gps_fixes` | separate download; sensitive raw coordinates |
