package com.roundearth.bikecomputer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.roundearth.bikecomputer.data.db.GpsFix
import com.roundearth.bikecomputer.data.db.GpsFixDao
import com.roundearth.bikecomputer.data.diagnostics.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Periodically records GPS fixes into the separate [GpsFix] table while collecting — optional
 * ANCHORING data for the dead-reckoned trajectory (heading-bias estimation + drift bounding). Raw
 * coordinates are sensitive, so they live in their own table and their own export/download; the
 * dead-reckoning model never depends on them.
 *
 * Best-effort: if ACCESS_FINE_LOCATION isn't granted, it simply does nothing (no crash, no nag) —
 * GPS logging is an optional correction aid, not a requirement.
 */
class LocationLogger(
    private val context: Context,
    private val dao: GpsFixDao,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Minimum time between fixes; one a minute matches the per-minute timeline. */
    private val minIntervalMs: Long = 60_000L,
    private val minDistanceM: Float = 5f,
) {
    private val lm: LocationManager? = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var listener: LocationListener? = null

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Idempotent. No-ops when GPS is unavailable or the permission isn't granted. */
    fun start() {
        if (listener != null) return
        val manager = lm ?: return
        if (!hasPermission()) {
            Diag.i(TAG, "GPS logging skipped: ACCESS_FINE_LOCATION not granted")
            return
        }
        // Prefer the fused provider (API 31+) — better fixes, indoors/assisted — and fall back to raw
        // GPS. Skip silently if neither provider exists/enabled rather than no-op confusingly.
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> {
                Diag.i(TAG, "GPS logging skipped: no enabled location provider")
                return
            }
        }
        val l = LocationListener { loc -> scope.launch { dao.insert(loc.toFix(nowMs())) } }
        listener = l
        try {
            manager.requestLocationUpdates(provider, minIntervalMs, minDistanceM, l, Looper.getMainLooper())
            Diag.i(TAG, "GPS logging started on $provider")
        } catch (e: SecurityException) {
            Diag.w(TAG, "GPS logging blocked: $e")
            listener = null
        } catch (e: IllegalArgumentException) {
            Diag.w(TAG, "GPS provider unavailable: $e") // no GPS_PROVIDER on this device
            listener = null
        }
    }

    fun stop() {
        listener?.let { lm?.removeUpdates(it) }
        listener = null
    }

    companion object {
        private const val TAG = "LocationLogger"

        /** Pure mapping from an Android [Location] to a stored [GpsFix] — exposed for tests. */
        fun Location.toFix(timestampMillis: Long): GpsFix = GpsFix(
            timestampMillis = timestampMillis,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            altitudeMeters = if (hasAltitude()) altitude else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            speedMps = if (hasSpeed()) speed else null,
        )
    }
}
