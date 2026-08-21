package com.batya.stopsmsspam.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.batya.stopsmsspam.data.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives every incoming SMS while this app holds the SMS role.
 *
 * This is the load-bearing part of being the default SMS app: the system delivers SMS_DELIVER to
 * exactly one app and does NOT write the message to the Telephony provider itself. If this
 * receiver fails to insert the row, the text is gone - it will not be in any messaging app, ever.
 * So the insert happens first and unconditionally, and only then do we try to notify.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return

        // A long text arrives as several PDUs that have to be stitched back together.
        val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val address = parts.first().displayOriginatingAddress ?: return
        val sentAt = parts.first().timestampMillis
        val subscriptionId = intent.getIntExtra(SUBSCRIPTION_EXTRA, -1)

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                SmsRepository(appContext).insertIncoming(
                    address = address,
                    body = body,
                    sentAtMillis = sentAt,
                    subscriptionId = subscriptionId,
                )
                Notifications.notifyIncomingSms(appContext, address, body)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to store incoming SMS from $address", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsDeliverReceiver"

        /** Undocumented but stable extra carrying the SIM subscription id. */
        const val SUBSCRIPTION_EXTRA = "subscription"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
