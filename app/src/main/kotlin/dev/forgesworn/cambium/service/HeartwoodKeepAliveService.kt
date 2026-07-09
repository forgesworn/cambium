package dev.forgesworn.cambium.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.forgesworn.cambium.MainActivity
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional, opt-in foreground service that keeps the process (and so [HeartwoodSession]'s warm
 * `NostrConnect`) alive between requests, closing the previously-tracked "the session is only
 * warm while the process happens to be running" gap. Off by default -- see
 * [PairingStore.isKeepAliveEnabled] -- toggled from `MainActivity`, and optionally restarted on
 * boot by [BootReceiver].
 *
 * `targetSdk` 35 requires an explicit `foregroundServiceType`; there is no built-in type that
 * describes "hold a NIP-46 relay connection open", so this follows Amber's own
 * `ConnectivityService` (verified against its source,
 * greenart7c3/Amber `service/ConnectivityService.kt` and its manifest): `specialUse`, declared
 * both in the manifest (with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` description) and passed
 * explicitly to [ServiceCompat.startForeground] on API 34+ (`UPSIDE_DOWN_CAKE`), where the
 * `foregroundServiceType` argument became mandatory.
 *
 * The periodic keepalive calls `getPublicKey()` through the shared [HeartwoodSession] -- a
 * read-only, always-safe operation Heartwood answers without a physical button. rust-nostr's
 * client bindings do not expose a lower-level "ping" primitive to call instead (checked via
 * `javap` against the actual AAR: `NostrConnect`/`NostrConnectInterface` have no `ping` method).
 */
class HeartwoodKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pairingStore: PairingStore
    private var pingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        pairingStore = PairingStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundServiceType)
        } catch (e: Exception) {
            // The OS can refuse a foreground-service start from the background on API 31+
            // (ForegroundServiceStartNotAllowedException); give up quietly and let the next
            // foreground-triggered start (the MainActivity toggle) retry.
            Log.w(TAG, "could not start in the foreground, stopping", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startPingLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                val pairing = pairingStore.current()
                if (pairing == null || !pairingStore.isKeepAliveEnabled()) {
                    break
                }
                when (val result = HeartwoodSession.withClient(pairing) { it.getPublicKey() }) {
                    is HeartwoodResult.Success -> Log.d(TAG, "keepalive ping ok")
                    is HeartwoodResult.Failure -> Log.d(TAG, "keepalive ping failed: ${result.error}")
                }
                delay(PING_INTERVAL_MILLIS)
            }
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keep_alive_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val TAG = "HeartwoodKeepAlive"
        private const val CHANNEL_ID = "heartwood_keep_alive"
        private const val NOTIFICATION_ID = 1
        private const val PING_INTERVAL_MILLIS = 4 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HeartwoodKeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeartwoodKeepAliveService::class.java))
        }
    }
}
