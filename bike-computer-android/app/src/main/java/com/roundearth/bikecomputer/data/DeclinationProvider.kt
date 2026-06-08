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
            // Take the freshest fix across all enabled providers (gps/network/passive).
            lm.getProviders(true)
                .mapNotNull { lm.getLastKnownLocation(it) }
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
    }
}
