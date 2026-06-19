# Live compass heading

## Current implementation

The dashboard compass updates continuously whenever the app is running,
independent of wheel motion.

- **Heading ticker:** `CscBleDataSource` runs a `headingJob` that polls
  `HeadingProvider` every **250 ms (4 Hz)** and writes `bearingDegrees` into the
  live `RawBikeData` state.
- **No redundant emissions:** state is only updated when the heading moves at
  least 1° (`HEADING_EPSILON_DEG`), so sub-degree sensor jitter on a stationary
  phone produces no churn.
- **Thread safety:** all live-state mutations go through `MutableStateFlow.update`
  (atomic compare-and-set) — the BLE-callback path (`emitData`), the stale-speed
  watcher, and the heading ticker all use the CAS, so they can write concurrently
  without clobbering each other's partial updates.
- **Lifecycle:** the ticker is a `Job` started in `start()` and cancelled in
  `stop()`, alongside the stale-speed watcher.
- **Recording:** events still capture `heading()` at the moment of each wheel
  revolution; northward reconstruction is unaffected.

Source of north is the rotation-vector sensor via `HeadingProvider`, which
reports **magnetic** north.

## The per-minute heading timeline

Separately from the live dashboard ticker and the per-revolution stamp, a
`HeadingLogger` records a durable **per-minute heading timeline** (the
`heading_minutes` table). This is the *direction* half of the 2-D displacement
reconstruction (`N = Σ Δrev·circ·cos θ`, `E = Σ Δrev·circ·sin θ`) for the
windows the live per-revolution stream is absent — see
[`backlog-and-displacement.md`](backlog-and-displacement.md) and
[ARCHITECTURE.md](../../ARCHITECTURE.md) → *2-D displacement reconstruction*.

- **One row per wall-clock minute, a circular mean of the minute's samples.** The
  logger samples `HeadingProvider` every ~2 s and folds the readings into a
  `CircularMean` (the only correct way to average angles across the 0/360 wrap —
  an arithmetic mean of 350° and 10° gives 170°, pointing *south*, and would
  invert that minute's displacement vector). It averages the whole minute rather
  than sampling once on the tick, so a single read landing mid-corner can't
  mislabel the minute. The final partial minute is flushed on `stop()` so the last
  minute of a ride isn't dropped.
- **Magnetic *and* true are both stored, so declination stays recoverable.** Each
  row keeps the circular-mean *magnetic* heading and the *true* heading derived
  with the declination in effect at write time — declination is recoverable
  per-row as `true − magnetic`, exactly as the per-revolution model does, so a
  later declination correction never rewrites stored rows.
- **The NaN→NULL invariant is load-bearing here too.** A minute the phone ran but
  the compass produced no valid sample is an explicit `NULL` heading row (never 0 =
  due north); NaN samples are skipped so one dropout can't poison the minute's
  mean. A minute with revolutions but a `NULL` heading is the *unknown-direction*
  case the reconstruction handles — its north/east are left blank, never read as
  north.
- **`compassAccuracy` is recorded per minute** (the worst
  `SensorManager.SENSOR_STATUS_ACCURACY_*` seen that minute; -1 = unknown).
  A constant magnetometer **bias is the dominant error** in 2-D dead-reckoning and
  is otherwise invisible, so it is surfaced to let a consumer discount
  low-confidence minutes (and to pair with the optional GPS anchoring log that
  estimates and subtracts that bias).
- **Sampled whenever the phone collects — independent of BLE.** Heading is a
  *phone* signal, so the logger runs off the foreground service
  (`BikeApplication.startCollection()`/`stopCollection()`), not the BLE
  connection: it records even with no sensor connected. A `PARTIAL_WAKE_LOCK` held
  by `CollectionService` keeps the once-a-minute ticker alive under Doze with the
  screen off (a plain timer would be frozen).
- **The "dark gap" asymmetry.** This is the mirror image of the firmware backlog:
  backlog **revolutions survive a BLE outage** (the sensor kept logging), but
  **per-minute heading exists only while the phone was running** the logger. So a
  recovered minute can have a known distance but an unknown direction — which is
  precisely why the reconstruction treats `NULL` heading as unknown direction
  rather than north.

## Related

- **True vs magnetic north:** see [true-north.md](true-north.md). A declination
  setting (manual, or auto-detected from location) converts magnetic to true.
- **Mounting offset:** `applyMountingOffset` (in `Heading.kt`) corrects for how
  the phone sits on the mount; calibrate it from Settings.
- **Sensor lifecycle:** `HeadingProvider` is owned by `CollectionService`, the
  foreground service that runs a ride, via `BikeApplication.startCollection()` /
  `stopCollection()` — **not** the app foreground. It deliberately runs for the
  whole ride (including while backgrounded or with the screen off), so every
  revolution recorded in the background still gets a real heading and the
  northward reconstruction stays intact; it stops only when collection ends. (An
  earlier design tore it down — along with the BLE source — on a
  `ProcessLifecycleOwner` background callback to save power, but that lost the
  heading on every background revolution.) See
  [ARCHITECTURE.md](../../ARCHITECTURE.md) → *Collection outlives the UI*.
