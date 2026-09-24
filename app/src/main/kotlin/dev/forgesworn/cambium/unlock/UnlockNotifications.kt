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
    private const val ID_QUIET = 2
    private const val ID_REQUEST_BASE = 0x1000
    private const val ID_STILL_LOCKED_BASE = 0x2000

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
            .build()
        post(context, ID_REQUEST_BASE + slot(enrolment.id), notification)
    }

    fun cancelRequest(context: Context, enrolmentId: Long) {
        NotificationManagerCompat.from(context).cancel(ID_REQUEST_BASE + slot(enrolmentId))
    }

    fun showStillLocked(context: Context, match: LockMatch) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.unlock_still_locked_title, match.enrolment.boardLabel))
            .setContentText(context.getString(R.string.unlock_still_locked_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.unlock_still_locked_text)))
            .setAutoCancel(true)
            .setContentIntent(openUnlock(context, match.enrolment.id))
            .build()
        post(context, ID_STILL_LOCKED_BASE + slot(match.enrolment.id), notification)
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
            .build()
        post(context, ID_QUIET, notification)
    }

    fun cancelQuiet(context: Context) = NotificationManagerCompat.from(context).cancel(ID_QUIET)

    private fun openUnlock(context: Context, enrolmentId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            slot(enrolmentId),
            UnlockActivity.intent(context, enrolmentId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun slot(enrolmentId: Long): Int = (enrolmentId and 0xFFF).toInt()

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
