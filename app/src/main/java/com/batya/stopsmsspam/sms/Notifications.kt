package com.batya.stopsmsspam.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.batya.stopsmsspam.MainActivity
import com.batya.stopsmsspam.R
import com.batya.stopsmsspam.data.model.BatchProgress
import kotlin.math.abs

/**
 * Notification plumbing.
 *
 * While this app holds the SMS role it is the only thing standing between an incoming text and
 * the user never seeing it, so [notifyIncomingSms] is not a nicety - it is the replacement for
 * the messaging app we displaced.
 */
object Notifications {

    const val CHANNEL_INCOMING = "incoming_sms"
    /**
     * Note the version suffix. A channel's importance is fixed when it is created - the system
     * lets an app lower it later but never raise it - so correcting this one from DEFAULT to
     * HIGH required a new id. Editing the constant alone would have changed nothing on any
     * device that had already run the app.
     */
    const val CHANNEL_OPT_OUT_CONFIRMED = "opt_out_confirmed_v2"
    private const val CHANNEL_OPT_OUT_CONFIRMED_LEGACY = "opt_out_confirmed"
    const val CHANNEL_BATCH = "batch_progress"

    const val BATCH_NOTIFICATION_ID = 1001
    private const val INCOMING_ID_BASE = 2000
    private const val CONFIRMED_ID_BASE = 3000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                "Incoming messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Texts that arrive while this app is your default SMS app."
            },
        )

        // The v1 channel sat at DEFAULT importance, which posts no heads-up banner - the
        // notification arrived silently in the shade and went unnoticed. Remove it so it does
        // not linger in the app's notification settings as a dead entry.
        runCatching { manager.deleteNotificationChannel(CHANNEL_OPT_OUT_CONFIRMED_LEGACY) }

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OPT_OUT_CONFIRMED,
                "Opt-out confirmations",
                // HIGH so it actually announces itself. Being told the STOP landed is the whole
                // point of clearing the message rather than hiding it, and a notification the
                // user does not see does not inform anyone. It stays a separate channel so it
                // can be turned down without touching real texts.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "When a sender confirms you have been unsubscribed."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BATCH,
                "Opt-out progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of a running bulk opt-out batch."
                setShowBadge(false)
            },
        )
    }

    fun notifyIncomingSms(context: Context, address: String, body: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(address)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notifySafely(context, INCOMING_ID_BASE + abs(address.hashCode() % 1000), notification)
    }

    /**
     * Reports that a sender acknowledged an opt-out, for the case where the message carrying
     * that acknowledgement was deleted on arrival.
     *
     * Dropping it silently would mean the user never learns the STOP worked - the one piece of
     * good news the whole exercise produces. So the message is still cleared from the inbox, and
     * this says what it said.
     */
    fun notifyOptOutConfirmed(context: Context, address: String, body: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_OPT_OUT_CONFIRMED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Unsubscribed from $address")
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$body\n\nThe message was cleared - you deleted this thread.")
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notifySafely(context, CONFIRMED_ID_BASE + abs(address.hashCode() % 1000), notification)
    }

    /**
     * MMS cannot be stored or rendered by this app (see [MmsDeliverReceiver]), so the user is
     * told plainly rather than left wondering why a picture message never arrived.
     */
    fun notifyMmsUnsupported(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("A picture or group message arrived")
            .setContentText("Stop SMS Spam cannot open MMS. Switch back to your normal messaging app to receive it.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Stop SMS Spam cannot open MMS while it is your default SMS app. " +
                        "The raw message was saved but not delivered. Hand the SMS role back to " +
                        "your normal messaging app and ask the sender to resend.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notifySafely(context, INCOMING_ID_BASE - 1, notification)
    }

    fun buildBatchNotification(context: Context, progress: BatchProgress): Notification {
        val title = when {
            !progress.running && progress.finished -> "Opt-out batch finished"
            progress.dryRun -> "Dry run in progress"
            else -> "Sending opt-out replies"
        }
        val text = buildString {
            append("${progress.completed} of ${progress.total}")
            if (progress.failedCount > 0) append(" - ${progress.failedCount} failed")
            if (progress.unconfirmedCount > 0) append(" - ${progress.unconfirmedCount} unconfirmed")
            if (progress.clearedCount > 0) append(" - ${progress.clearedCount} cleared")
            progress.currentAddress?.let { append(" - $it") }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_BATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(progress.running)
            .setOnlyAlertOnce(true)
            .setProgress(progress.total, progress.completed, false)
            .setContentIntent(openAppIntent(context))

        if (progress.running) {
            builder.addAction(
                0,
                "Cancel",
                PendingIntent.getService(
                    context,
                    0,
                    Intent(context, com.batya.stopsmsspam.bulk.BulkReplyService::class.java)
                        .setAction(com.batya.stopsmsspam.bulk.BulkReplyService.ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    /**
     * Builds the batch notification and posts it, so callers never touch NotificationManager
     * directly. Beyond removing the duplication, it keeps every `notify` behind
     * [notifySafely]'s explicit SecurityException catch - which is also the shape lint
     * recognises as guarding POST_NOTIFICATIONS. Callers that wrapped their own `notify` in
     * `runCatching` were equally safe at runtime but reported as unguarded, because lint does
     * not see through it.
     */
    fun notifyBatchProgress(context: Context, progress: BatchProgress) {
        notifySafely(context, BATCH_NOTIFICATION_ID, buildBatchNotification(context, progress))
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * POST_NOTIFICATIONS is a runtime permission on Android 13+; if the user declined it we
     * still want the message persisted, so a missing permission must not throw.
     */
    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications are off. The message is already in the provider either way.
        }
    }
}
