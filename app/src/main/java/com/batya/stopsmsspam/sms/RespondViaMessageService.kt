package com.batya.stopsmsspam.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles "reply with a message" from the incoming-call screen.
 *
 * The system requires the default SMS app to expose this service - without it the app does not
 * qualify for the SMS role at all - so it is implemented rather than stubbed: the quick-reply
 * text is sent to the caller and logged to the Sent box like any other outgoing message.
 */
class RespondViaMessageService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipient = intent?.data?.schemeSpecificPart?.trim()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)

        if (!recipient.isNullOrEmpty() && !text.isNullOrEmpty()) {
            scope.launch {
                try {
                    SmsSender.sendText(applicationContext, recipient, text, subscriptionId = -1)
                } catch (t: Throwable) {
                    Log.e(TAG, "Quick reply to $recipient failed", t)
                } finally {
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    private companion object {
        const val TAG = "RespondViaMessage"
    }
}
