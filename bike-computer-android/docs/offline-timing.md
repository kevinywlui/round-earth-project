# Offline timing: `cumulative_event_time_1024`

## What the column is

Every recorded revolution carries `cumulative_event_time_1024` — a **monotonic
sensor clock in 1/1024 s units**, built by `CscMeasurementDecoder` by unwrapping
the sensor's 16-bit CSC event time (which itself wraps every 64 s) and
accumulating it across packets. It starts at 0 on the first packet of a
connection.

The point of this column is **jitter-free offline speed reconstruction**. The
wall-clock receive time (`timestamp_ms`) carries BLE delivery jitter (tens of
ms), so a per-revolution speed computed from it is noisy. The sensor's own event
time does not have that jitter, so **while moving, successive-revolution deltas
of `cumulative_event_time_1024` are exact**. That is the quantity the (external,
not-yet-written) speed-reconstruction tool should divide distance by.

## Rules for the CSV consumer

Four rules the analysis tool must follow. They match the in-code source of truth,
the KDoc on `CscMeasurementDecoder.Result.cumulativeEventTime1024`; this doc is the
copy a CSV consumer will actually find.

1. **It is monotonic, in 1/1024 s.** Within one connection segment it never goes
   backward. Divide by 1024 to get seconds.

2. **A non-increasing step = a new connection segment.** The accumulator lives in
   the decoder, and a reconnect builds a *fresh* decoder, so the series **resets
   to 0 on every reconnect**. When `cumulative_event_time_1024` does not increase
   between two consecutive rows — a backward jump, or a drop/repeat back to the
   baseline `0` (which happens when a segment delivered only its baseline row
   before disconnecting, so a strict *backward* check would miss the `0 → 0`
   seam) — treat that as a connection-segment boundary and bridge the gap using
   `timestamp_ms` (wall-clock epoch millis). Do **not** subtract across the
   boundary. (A sensor reboot or a long stop, by contrast, stays *within* one
   segment — see rule 3 — and advances forward, it does not reset.)

3. **A forward step on a `delta_revolutions == 0` row is an estimate, not
   jitter-free.** With this firmware (which notifies strictly once per real
   revolution and emits no keepalive/liveness packets) a `delta_revolutions == 0`
   row after the baseline is **not** a stationary coast — it is a sensor reboot,
   or a stop ≥ 64 s where the 16-bit event time has wrapped past disambiguation.
   For those ambiguous gaps the accumulator's advance is *estimated from the wall
   clock*, so it keeps the series monotonic but is **not** the exact sensor delta.
   Do not treat such steps as precise timing.

4. **Only divide when `delta_revolutions > 0`.** Speed = (distance from
   `delta_revolutions × wheel_circumference_m`) / (Δ`cumulative_event_time_1024`
   in seconds). Guard against `delta_revolutions == 0` to avoid both a
   divide-by-revs==0 and treating an estimated gap as real motion.

## Caveats worth knowing

- **Two clocks, same rate, do not mix within a segment.** The in-segment
  estimates in rule 3 come from the monotonic clock fed to the decoder
  (`elapsedRealtime`); the cross-segment bridge in rule 2 uses the persisted
  wall-clock `timestamp_ms`. They run at the same rate but are different clocks —
  bridge segments with `timestamp_ms` only, and reconstruct speed within a
  segment from `cumulative_event_time_1024` only.

- **The wrap discriminator is a wall-clock proxy.** Whether a gap "wrapped" is
  decided from the measured wall-clock gap against 64 s, not from the sensor
  crystal directly. A real gap landing within a few hundred ms of 64 s could be
  misclassified, under-counting that one gap while staying monotonic. It needs
  sub-second wall-clock jitter exactly at the boundary, so in practice the
  "exact while moving" guarantee holds — but a single anomalous delta right at a
  ~64 s gap is the one place to distrust.

## The backlog timeline: a separate, estimated clock

The per-minute **backlog** (revolutions the sensor logged while the app was
disconnected, recovered from its on-device flash on reconnect — see
[ARCHITECTURE.md](../../ARCHITECTURE.md) → *Per-minute robustness* and
[`backlog-and-displacement.md`](backlog-and-displacement.md)) carries its *own*
wall-clock, on entirely different terms from the per-revolution series above.
Treat it as a distinct clock with its own contract:

1. **The backlog wall-clock is ESTIMATED — never mix it with
   `cumulative_event_time_1024`.** Each `backlog_minutes` row's
   `wallClockMillis` is *back-computed*, not measured: on connect the app reads
   the Backlog **Info** block (`boot_id` + the sensor's current uptime) and pairs
   it with `System.currentTimeMillis()` at that instant — a single
   **uptime → wall-clock anchor**. A record's time is then
   `anchorWallClock − (anchorUptime − record.uptime_s)`. The two series are
   unrelated clocks at different resolutions (per-minute vs 1/1024 s); a consumer
   must **not** subtract one from the other or stitch them into one timeline.

2. **The anchor is precise for the *current* boot, a lower bound for prior
   boots.** The sensor has **no RTC**, so it can place a record only relative to a
   boot it is still inside. For records whose `boot_id` matches the Info block's,
   the offset is exact (same uptime origin). For records from a *prior* boot (the
   sensor rebooted while disconnected), the off-duration between boots is
   *unknowable*, so those rows are pinned at the current boot's start — an
   **estimate / lower bound**, not a measurement. Don't read prior-boot minute
   timestamps as exact.

3. **Distance keys on revolutions, so it is clock-independent.** The recovered
   *distance* never depends on any of the above timing: it is the span of
   `cumulativeRevolutions` **within one `boot_id`** × circumference (a reboot
   resets the counter, so deltas are never taken across a boot boundary). Even
   when the wall-clock for a window is a rough estimate, its distance — and hence
   its contribution to the displacement vector — is sound.

4. **The keys are `(sensorMac, boot_id, record_index)`.** `record_index` is the
   sensor's global, reboot-surviving write counter, so `(sensorMac, record_index)`
   is unique forever and the app dedups on it with `INSERT OR IGNORE` (every
   reconnect re-streams the whole ring; the dedup makes that a no-op). `boot_id`
   distinguishes the cumulative-counter resets a reboot causes — it is the group
   key for the within-boot delta, not an idempotency key on its own (a sub-stable
   boot can reuse a `boot_id`, which is exactly why `record_index` carries the
   uniqueness).
