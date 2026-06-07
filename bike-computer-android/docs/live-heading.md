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
  (atomic compare-and-set), so the BLE callback, stale-speed watcher, and heading
  ticker can write concurrently without clobbering each other.
- **Lifecycle:** the ticker is a `Job` started in `start()` and cancelled in
  `stop()`, alongside the stale-speed watcher.
- **Recording:** events still capture `heading()` at the moment of each wheel
  revolution; northward reconstruction is unaffected.
- **Simulator:** `SimulatedBikeDataSource` animates its own bearing (the emulator
  has no magnetometer), so it needs no ticker.

Source of north is the rotation-vector sensor via `HeadingProvider`, which
reports **magnetic** north.

## Next steps

- **True vs magnetic north:** rotation-vector gives magnetic north. Apply
  declination (reuse the repo's `sunsight` math) for true-north bearing and
  northing.
- **Mounting offset:** heading reflects phone orientation; add a calibration
  offset for handlebar mounts not aligned with travel.
- **Sensor lifecycle:** `HeadingProvider` starts in `BikeApplication.onCreate`
  and runs for the process lifetime. Consider tying start/stop to the recording
  session or app foreground state to save power.
