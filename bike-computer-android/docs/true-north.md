# True north compass

## Current implementation

The dashboard shows two compasses side by side — **TRUE** and **MAGNETIC** —
and every recorded revolution stores both headings.

- **Magnetic heading** comes from the rotation-vector sensor via
  `HeadingProvider` (clockwise from magnetic north).
- **True heading** = `trueFromMagnetic(magnetic, declination)` =
  `(magnetic + declination) mod 360`, where declination is positive east. The
  shared helper lives in `data/Heading.kt`.
- **Declination has two entry paths.** The manual setting in Settings →
  *Magnetic declination* is authoritative; default is 0°, so true == magnetic
  until set (look-up source: magnetic-declination.com). Settings also has an
  **AUTO-DETECT FROM LOCATION** button: it requests the
  `ACCESS_COARSE_LOCATION` permission on demand and `DeclinationProvider`
  computes declination from the device's *last known* fix via the platform
  `GeomagneticField` (World Magnetic Model, no Play Services). It is a one-shot
  assist that just pre-fills the manual setting — there is no live location
  lifecycle, so if there is no recent fix it reports "no fix" and the user opens
  a maps app once to get one, then retries.
- **Live state:** `RawBikeData` carries both `bearingDegrees` (magnetic) and
  `trueBearingDegrees`. The heading ticker and per-revolution path update both;
  the ticker still gates on the 1° jitter threshold (now across both values,
  using wrap-aware `angularDistance` so the 0/360 boundary doesn't churn).
- **Storage:** `RevolutionEvent` stores both `headingDegrees` (magnetic) and
  `trueHeadingDegrees`, and CSV export carries a `true_heading_degrees` column.
  There is no hand-written Room migration: schema changes use
  `fallbackToDestructiveMigration()` (a version bump wipes history on purpose —
  this is a single-user app), so don't expect old rides to survive a schema
  change. Declination is still recoverable per-row as `true − magnetic`, so the
  stored data is lossless even though declination is a mutable preference.

## Why manual, not solar declination

The `sunsight/` page computes **solar** declination (the sun's angle above the
celestial equator, from day-of-year) — a different quantity from **magnetic**
declination (magnetic vs geographic north at a location). Solar declination
cannot correct a compass, so it is not reused here.

## Next steps

- **Live location for auto-declination:** the current auto-detect reads only the
  *last known* fix. Requesting a live fix would let it work cold (no maps app
  needed first), at the cost of a location lifecycle the app currently avoids on
  purpose.
