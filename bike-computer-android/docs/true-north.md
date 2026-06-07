# True north compass

## Current implementation

The dashboard shows two compasses side by side — **TRUE** and **MAGNETIC** —
and every recorded revolution stores both headings.

- **Magnetic heading** comes from the rotation-vector sensor via
  `HeadingProvider` (clockwise from magnetic north).
- **True heading** = `trueFromMagnetic(magnetic, declination)` =
  `(magnetic + declination) mod 360`, where declination is positive east. The
  shared helper lives in `data/Heading.kt`.
- **Declination is a manual setting.** The device has no live GPS fix (the
  location permission is capped at API 30 for BLE scanning only), so the user
  enters their local declination in Settings → *Magnetic declination*. Default
  is 0°, so true == magnetic until set. Look-up source:
  magnetic-declination.com.
- **Live state:** `RawBikeData` carries both `bearingDegrees` (magnetic) and
  `trueBearingDegrees`. The heading ticker and per-revolution path update both;
  the ticker still gates on the 1° jitter threshold (now across both values,
  using wrap-aware `angularDistance` so the 0/360 boundary doesn't churn).
- **Storage:** `RevolutionEvent` keeps the existing `headingDegrees` (magnetic)
  and adds `trueHeadingDegrees` (DB v3, `MIGRATION_2_3`). The migration backfills
  legacy rows from `headingDegrees` (old declination unknown, so assumed 0) so
  historical exports don't show a bogus 0° true heading. CSV export gains a
  `true_heading_degrees` column. Declination is recoverable as
  `true − magnetic`, so storage stays lossless even though declination is a
  mutable preference.
- **Simulator:** `SimulatedBikeDataSource` applies the same declination to its
  synthetic bearing, so both compasses and both stored columns are populated on
  the emulator too.

## Why manual, not solar declination

The `sunsight/` page computes **solar** declination (the sun's angle above the
celestial equator, from day-of-year) — a different quantity from **magnetic**
declination (magnetic vs geographic north at a location). Solar declination
cannot correct a compass, so it is not reused here.

## Next steps

- **GPS auto-declination:** add a location fix and derive declination from
  `android.hardware.GeomagneticField(lat, lon, alt, time)` so the user need not
  enter it. Requires runtime location permission across all SDKs and a location
  lifecycle.
- **Mounting offset:** heading still reflects phone orientation, not travel
  direction; add a calibration offset for handlebar mounts.
