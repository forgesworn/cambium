package dev.forgesworn.cambium.unlock

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.forgesworn.cambium.MainActivity
import dev.forgesworn.cambium.R

/**
 * The three things phone unlock tells the owner: a board is asking to be unlocked (high
 * importance, it is the point of the feature), a board this phone answered is still locked, and a
 * paired signer has gone quiet. Every unlock notification carries the standing warning, because a
 * thief holding the board can make it ask.
 */
object UnlockNotifications {
    private const val CHANNEL_REQUESTS = "unlock_requests"
    private const val CHANNEL_STATUS = "signer_status"
    // Notifications are told apart by tag, then by the board's own 32-bit record id, so two
    // boards never share one (a folded id would collide one time in a few thousand).
    private const val TAG_REQUEST = "unlock_request"
    private const val TAG_STILL_LOCKED = "unlock_still_locked"
    private const val TAG_QUIET = "signer_quiet"
    private const val ID_QUIET = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REQUESTS,
                context.getString(R.string.unlock_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.unlock_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.status_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.status_channel_description) },
        )
    }

    /** "power-on, devolo-753, restart #212" */
    fun describe(context: Context, lock: LockContext): String {
        val network = lock.ssid.ifBlank { context.getString(R.string.unlock_unknown_network) }
        return context.getString(R.string.unlock_context_line, lock.reset.ifBlank { "restart" }, network, lock.boot)
    }

    fun lastHeard(context: Context, enrolment: UnlockEnrolment): String? {
        val signer = enrolment.signerPubkeyHex ?: return null
        val at = UnlockStore(context).reachability()[signer]?.lastSuccessAtMillis ?: return null
        val ago = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return context.getString(R.string.unlock_last_heard, ago)
    }

    fun showRequest(context: Context, match: LockMatch) {
        val enrolment = match.enrolment
        val lines = listOfNotNull(
            describe(context, match.context),
            lastHeard(context, enrolment),
            context.getString(R.string.unlock_warning),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.unlock_notification_title, enrolment.boardLabel))
            .setContentText(context.getString(R.string.unlock_notification_text, describe(context, match.context)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openUnlock(context, enrolment.id))
            .addAction(0, context.getString(R.string.unlock_action), openUnlock(context, enrolment.id))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted(context, CHANNEL_REQUESTS, R.string.unlock_notification_public))
            .setGroup("$TAG_REQUEST:${enrolment.id}")
            .build()
        post(context, TAG_REQUEST, slot(enrolment.id), notification)
    }

    fun cancelRequest(context: Context, enrolmentId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG_REQUEST, slot(enrolmentId))
    }

    fun showStillLocked(context: Context, match: LockMatch) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.unlock_still_locked_title, match.enrolment.boardLabel))
            .setContentText(context.getString(R.string.unlock_still_locked_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.unlock_still_locked_text)))
            .setAutoCancel(true)
            .setContentIntent(openUnlock(context, match.enrolment.id))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted(context, CHANNEL_STATUS, R.string.unlock_status_public))
            .setGroup("$TAG_STILL_LOCKED:${match.enrolment.id}")
            .build()
        post(context, TAG_STILL_LOCKED, slot(match.enrolment.id), notification)
    }

    /** One notification for every quiet signer, so three identities on one board are one alert. */
    fun showQuiet(context: Context, labels: List<String>) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.quiet_title, labels.joinToString(", ")))
            .setContentText(context.getString(R.string.quiet_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.quiet_text)))
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted(context, CHANNEL_STATUS, R.string.unlock_status_public))
            .setGroup(TAG_QUIET)
            .build()
        post(context, TAG_QUIET, ID_QUIET, notification)
    }

    fun cancelQuiet(context: Context) = NotificationManagerCompat.from(context).cancel(TAG_QUIET, ID_QUIET)

    /** What the lock screen shows instead: no board name, network or restart count. */
    private fun redacted(context: Context, channel: String, text: Int): android.app.Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(text))
            .build()

    /** The intent's data URI names the board, so each board has its own PendingIntent. */
    private fun openUnlock(context: Context, enrolmentId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            UnlockActivity.intent(context, enrolmentId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Board record ids are u32: every one maps to a distinct Int. */
    private fun slot(enrolmentId: Long): Int = enrolmentId.toInt()

    /**
     * Every notification gets a group of its own. Android otherwise bundles an app's ungrouped
     * notifications (here: the prompt with the keep-alive's ongoing one) under a summary, and a
     * tap on that summary opens the app's launcher screen instead of the unlock screen.
     */
    private fun post(context: Context, tag: String, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)
        NotificationManagerCompat.from(context).notify(tag, id, notification)
    }
}
