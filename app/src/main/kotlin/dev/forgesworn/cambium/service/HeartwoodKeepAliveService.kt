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
import dev.forgesworn.cambium.pairing.Pairing
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.HeartwoodRequestPriority
import dev.forgesworn.cambium.signer.HeartwoodResult
import dev.forgesworn.cambium.signer.HeartwoodSession
import dev.forgesworn.cambium.signer.displayLabel
import dev.forgesworn.cambium.signer.isPolicyRefusal
import dev.forgesworn.cambium.unlock.Reachability
import dev.forgesworn.cambium.unlock.UnlockCoordinator
import dev.forgesworn.cambium.unlock.UnlockNotifications
import dev.forgesworn.cambium.unlock.UnlockStore
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
 *
 * The ping goes through [HeartwoodSession.trySilent], the shedding path, not [HeartwoodSession.withClient]:
 * a real request against a slow or unreachable Heartwood can occupy that identity's worker for up
 * to [HeartwoodSession]'s silent timeout. A keepalive has maintenance priority and can only enter
 * an empty worker, so it never consumes one of the slots reserved for real Amethyst work.
 * `trySilent` refuses it immediately if the queue is non-empty, which is also the right
 * behaviour here on its own terms: a busy queue means the session is demonstrably warm already,
 * so a skipped ping loses nothing.
 *
 * One cycle pings every paired identity in turn, not just one -- each has its own
 * [HeartwoodSession] worker and admission control (see its class doc), so a slow/unreachable
 * identity's ping can never delay or shed another identity's. With a realistic handful of paired
 * signers this stays well inside [PING_INTERVAL_MILLIS]; there is no per-identity scheduling here,
 * only a single sequential sweep every cycle.
 *
 * Phone unlock rides on the same service. While any board is set up to be unlocked from this
 * phone ([UnlockStore]), the service runs whether or not the keep-warm toggle is on, and keeps
 * [UnlockCoordinator]'s lock listener alive: the point is to hear the board after a power cut
 * while the owner is away. The pings' results feed [Reachability], which raises one "gone quiet"
 * notification when paired signers stop answering. Only these scheduled pings count: Cambium
 * never pings because of something it saw on a relay, since a ping seconds after a lock message
 * would tie that message to this phone's stable NIP-46 key.
 */
class HeartwoodKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pairingStore: PairingStore
    private lateinit var unlockStore: UnlockStore
    private var pingJob: Job? = null
    /** The signers named in the "gone quiet" notification now showing, so it is posted once per change. */
    private var quietShown: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        pairingStore = PairingStore(this)
        unlockStore = UnlockStore(this)
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
        CoroutineScope(Dispatchers.IO).launch { UnlockCoordinator.stop() }
        super.onDestroy()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                val pairings = pairingStore.pairings()
                val pinging = pairings.isNotEmpty() && pairingStore.isKeepAliveEnabled()
                if (!pinging && !unlockStore.hasEnrolments()) {
                    break
                }
                // Cheap when nothing changed; picks up a board set up or forgotten since.
                UnlockCoordinator.sync(this@HeartwoodKeepAliveService)
                if (pinging) {
                    pingAll(pairings)
                }
                delay(PING_INTERVAL_MILLIS)
            }
            UnlockCoordinator.stop()
            stopSelf()
        }
    }

    private suspend fun pingAll(pairings: List<Pairing>) {
        val records = unlockStore.reachability().filterKeys { key -> pairings.any { it.signerPubkeyHex == key } }.toMutableMap()
        for (pairing in pairings) {
            val tag = pairing.signerPubkeyHex.take(8)
            val result = HeartwoodSession.trySilent(
                pairing,
                priority = HeartwoodRequestPriority.MAINTENANCE,
            ) { it.getPublicKey() }?.result
            when (result) {
                is HeartwoodResult.Success -> Log.d(TAG, "keepalive ping ok ($tag)")
                is HeartwoodResult.Failure -> Log.d(TAG, "keepalive ping failed ($tag): ${result.error}")
                null -> Log.d(TAG, "keepalive ping skipped ($tag): worker already busy")
            }
            // A refusal is an answer: the signer is there. A skipped ping says nothing either way.
            if (result != null) {
                val answered = result is HeartwoodResult.Success ||
                    (result is HeartwoodResult.Failure && isPolicyRefusal(result.error))
                records[pairing.signerPubkeyHex] =
                    Reachability.afterPing(records[pairing.signerPubkeyHex], answered, System.currentTimeMillis())
            }
        }
        unlockStore.setReachability(records)
        updateQuietAlert(pairings, records)
    }

    /**
     * One notification naming every signer that has gone quiet. A signer that shares a relay with
     * a board asking to be unlocked right now is left out: its silence is explained by the unlock
     * prompt already on screen.
     */
    private fun updateQuietAlert(pairings: List<Pairing>, records: Map<String, Reachability.Record>) {
        val asking = unlockStore.enrolments().filter { UnlockCoordinator.currentRequest(it.id) != null }
        val quiet = pairings
            .filter { Reachability.isQuiet(records[it.signerPubkeyHex]) }
            .filterNot { pairing -> asking.any { board -> board.relays.any { relay -> pairing.relays.any { it.trimEnd('/') == relay } } } }
            .map { it.displayLabel() }
            .sorted()
        if (quiet == quietShown) return
        quietShown = quiet
        if (quiet.isEmpty()) {
            UnlockNotifications.cancelQuiet(this)
        } else {
            UnlockNotifications.showQuiet(this, quiet)
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
            .setContentText(
                getString(
                    if (unlockStore.hasEnrolments()) R.string.keep_alive_notification_text_listening
                    else R.string.keep_alive_notification_text,
                ),
            )
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
        private const val PING_INTERVAL_MILLIS = 8 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HeartwoodKeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeartwoodKeepAliveService::class.java))
        }
    }
}
