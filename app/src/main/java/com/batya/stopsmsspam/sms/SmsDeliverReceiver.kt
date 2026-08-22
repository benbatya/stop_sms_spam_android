package com.batya.stopsmsspam.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.batya.stopsmsspam.data.OptOutConfirmationDetector
import com.batya.stopsmsspam.data.SenderMemory
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
                if (dropAsHandledResponse(appContext, address, body)) return@launch

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

    /**
     * Drops a reply from a sender whose thread the user deleted, rather than storing a message
     * they would only have to delete again.
     *
     * Deliberately limited to the acknowledgement. Dropping *everything* from the number would
     * also swallow a later marketing message - and that message is the whole basis of the STOP
     * IGNORED state, so the escalation this method takes care to preserve would become
     * unreachable by the same stroke.
     *
     * The confirmation is recorded before it is discarded: the app otherwise learns that a
     * sender acknowledged an opt-out by reading this very message.
     *
     * @return true if the message was handled and must not be stored.
     */
    private suspend fun dropAsHandledResponse(
        context: Context,
        address: String,
        body: String,
    ): Boolean {
        if (!OptOutConfirmationDetector.isConfirmation(body)) return false

        val memory = SenderMemory(context)
        if (!memory.shouldAutoDeleteResponses(address)) return false

        memory.rememberConfirmation(address, System.currentTimeMillis())
        Log.i(TAG, "Dropped opt-out confirmation from a deleted thread")
        return true
    }

    private companion object {
        const val TAG = "SmsDeliverReceiver"

        /** Undocumented but stable extra carrying the SIM subscription id. */
        const val SUBSCRIPTION_EXTRA = "subscription"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
