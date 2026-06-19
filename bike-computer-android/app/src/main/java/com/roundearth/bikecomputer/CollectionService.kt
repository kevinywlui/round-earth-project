package com.roundearth.bikecomputer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.roundearth.bikecomputer.data.BikeData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground service that owns ride collection so it survives the app being backgrounded or the
 * screen locking — the prerequisite for hours-long, unattended recording.
 *
 * Why a service at all: BLE collection used to be tied to the process foreground lifecycle (a
 * [androidx.lifecycle.ProcessLifecycleOwner] observer in [BikeApplication] tore the radio down on
 * `onStop`). That is correct for a screen-on glance app but loses every revolution the moment the
 * phone is pocketed. Hosting collection in a foreground service moves that ownership: the OS keeps
 * the service (and its process) alive while a persistent notification is showing, and a backgrounded
 * *service* is not subject to the scan throttling a backgrounded *app* is.
 *
 * The collection pipeline itself ([BikeRepository] + [CscBleDataSource] + Room) is unchanged and
 * still lives on [BikeApplication]; the service only re-hosts its start/stop and the magnetometer,
 * so the "smart collector / dumb sensor" split is preserved — this is purely an app-side change.
 */
class CollectionService : Service() {

    private val app get() = application as BikeApplication

    // Drives the live notification off the same data the dashboard reads. Cancelled in onDestroy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Held while collecting so the once-a-minute heading sampler (and any timer work) keeps firing
    // with the screen off — a plain coroutine timer is frozen under Doze. Released in onDestroy.
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's "Stop" action loops back here as a start command; treat it as "end
        // the ride" and let onDestroy() do the teardown.
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground BEFORE touching BLE. connectedDevice is the FGS type for a BLE
        // peripheral (Android 14+ rejects startForeground without a declared type), and it is
        // backed by the BLUETOOTH_CONNECT runtime grant the app already holds.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(getString(R.string.collection_notification_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // Keep the CPU awake for the duration so the per-minute heading sampler isn't frozen by Doze
        // with the screen off. Idempotent: a sticky restart won't stack locks (acquire only if null).
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKELOCK_TAG").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // Collection + the magnetometer are owned by the service now, so both run for the whole
        // ride regardless of UI visibility. startCollection() is idempotent, so a sticky restart
        // re-enters here harmlessly and the session-resume window stitches the ride back together.
        app.startCollection()

        app.repository.bikeData
            .onEach { notifyManager().notify(NOTIF_ID, buildNotification(formatProgress(it))) }
            .launchIn(scope)

        // START_STICKY: if the OS kills us under memory pressure mid-ride, restart with a null
        // intent and resume — the recording job and session-resume window pick the ride back up.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // Stop the BLE source and the magnetometer; the recording job stays idle (not torn down)
        // so a later restart resumes the ride instead of re-seeding the odometer from zero.
        app.stopCollection()
        super.onDestroy()
    }

    private fun formatProgress(d: BikeData): String {
        val distUnit = if (d.useImperial) "mi" else "km"
        val speedUnit = if (d.useImperial) "mph" else "km/h"
        return getString(R.string.collection_notification_text)
            .format(d.odometer, distUnit, d.speed, speedUnit)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CollectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.collection_notification_title))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)          // the user can't swipe a live ride away by accident
            .setSilent(true)           // it's a persistent status, not an alert
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.collection_notification_stop), stop)
            .build()
    }

    private fun createChannel() {
        // minSdk 26, so a channel is always required. IMPORTANCE_LOW: visible and ongoing, but
        // no sound or heads-up peek.
        notifyManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.collection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun notifyManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "ride_collection"
        private const val NOTIF_ID = 1
        private const val WAKELOCK_TAG = "bikecomputer:collection"
        const val ACTION_STOP = "com.roundearth.bikecomputer.action.STOP_COLLECTION"
    }
}
