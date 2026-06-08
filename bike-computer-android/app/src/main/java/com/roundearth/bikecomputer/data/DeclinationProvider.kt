package com.roundearth.bikecomputer.data

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.location.LocationManager
import android.util.Log

/**
 * Computes the local magnetic declination (degrees, positive east) from the device's
 * last known location, so the user doesn't have to look it up by hand on
 * magnetic-declination.com and risk silently corrupting the true-north reconstruction
 * with a wrong value.
 *
 * Uses the platform [GeomagneticField] World Magnetic Model — no Google Play Services
 * dependency. It reads a *last known* fix rather than requesting a live one: it's a
 * one-shot "set it and forget it" calibration and a coarse fix is plenty (declination
 * varies slowly over tens of kilometers).
 *
 * Last-known fixes are spatially coarse-enough but can be stale in TIME: a fix cached
 * in a previous city would calibrate true-north to the wrong place. So fixes older than
 * [MAX_FIX_AGE_MS] are rejected, falling back to the "no location" path (null) — an honest
 * "no fix" beats a confidently-wrong declination for a value the user sets once.
 *
 * The caller must hold a location permission (COARSE is enough) before calling.
 */
class DeclinationProvider(private val context: Context) {

    /**
     * Best-effort declination (deg, +E) for the current location at [nowMillis], or null
     * if no location is available (no recent fix, location off, or permission missing).
     */
    @SuppressLint("MissingPermission") // caller gates on the runtime permission
    fun currentDeclinationDegrees(nowMillis: Long): Float? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = try {
            // Take the freshest fix across all enabled providers (gps/network/passive),
            // ignoring any older than MAX_FIX_AGE_MS so we don't calibrate from a fix left
            // over in a previous location.
            lm.getProviders(true)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .filter { nowMillis - it.time <= MAX_FIX_AGE_MS }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing for declination lookup", e)
            return null
        } ?: return null

        return GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            nowMillis,
        ).declination
    }

    private companion object {
        const val TAG = "DeclinationProvider"
        // Reject last-known fixes older than this; a day-old fix is fine spatially for a
        // city-scale declination but guards against calibrating from a previous trip.
        const val MAX_FIX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
