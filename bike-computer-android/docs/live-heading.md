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

## Related

- **True vs magnetic north:** see [true-north.md](true-north.md). A declination
  setting (manual, or auto-detected from location) converts magnetic to true.
- **Mounting offset:** `applyMountingOffset` (in `Heading.kt`) corrects for how
  the phone sits on the mount; calibrate it from Settings.
- **Sensor lifecycle:** `HeadingProvider` is tied to the app foreground —
  `BikeApplication` registers a `ProcessLifecycleOwner` observer that
  start()s/stop()s it (and the BLE source) on foreground/background to save
  power.
