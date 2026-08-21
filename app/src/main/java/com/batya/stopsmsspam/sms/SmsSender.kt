package com.batya.stopsmsspam.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.batya.stopsmsspam.data.PhoneAddress
import com.batya.stopsmsspam.data.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

sealed interface SendResult {
    data object Success : SendResult

    /** The radio refused it. This message did not go out and will not. */
    data class Failure(val reason: String) : SendResult

    /**
     * Handed to the system but unanswered when we stopped waiting - almost always the short-code
     * confirmation dialog still sitting on screen. It may still send, so the caller must not
     * report failure and must not mark anything read.
     */
    data class Unconfirmed(val reason: String) : SendResult
}

/**
 * Sends one SMS and waits for the radio to say what happened.
 *
 * Waiting matters here. `sendTextMessage` returns immediately, so without the sent-callback a
 * paced batch would report success for messages the radio silently dropped, and - worse - would
 * keep firing into a throttle it could not see.
 */
object SmsSender {

    private const val SENT_ACTION_PREFIX = "com.batya.stopsmsspam.SMS_SENT"

    /** A normal number either goes out or errors quickly; nothing waits on a human. */
    private const val DIRECT_TIMEOUT_MS = 45_000L

    /**
     * Short codes can raise a system confirmation dialog, so the wait has to be long enough for
     * the user to notice it and tap through - otherwise every short code in a batch gets written
     * off while its dialog is still open.
     */
    private const val SHORT_CODE_TIMEOUT_MS = 180_000L

    private val requestCodes = AtomicInteger(1)

    suspend fun sendText(
        context: Context,
        address: String,
        body: String,
        subscriptionId: Int,
        timeoutMillis: Long = defaultTimeoutFor(address),
    ): SendResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext SendResult.Failure("SEND_SMS permission is not granted")
        }

        val smsManager = smsManagerFor(context, subscriptionId)
            ?: return@withContext SendResult.Failure("No SMS subsystem available")

        val token = requestCodes.getAndIncrement()
        val action = "$SENT_ACTION_PREFIX.$token"

        val resultCode = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        if (continuation.isActive) continuation.resume(resultCode)
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                continuation.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                }

                // FLAG_MUTABLE so the framework can attach its error code; the explicit package
                // keeps it legal under the Android 14 ban on mutable implicit PendingIntents.
                val sentIntent = PendingIntent.getBroadcast(
                    context,
                    token,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

                try {
                    smsManager.sendTextMessage(address, null, body, sentIntent, null)
                } catch (t: Throwable) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (continuation.isActive) {
                        continuation.resume(FAILED_TO_DISPATCH)
                    }
                }
            }
        }

        when (resultCode) {
            null -> SendResult.Unconfirmed(
                if (PhoneAddress.isShortCode(address)) {
                    "Android is still asking you to confirm this short code. Answer the dialog - " +
                        "tick \"Remember my choice\" so it stops asking - then re-run this sender."
                } else {
                    "No confirmation from the network yet. It may still send."
                },
            )
            Activity.RESULT_OK -> {
                // Now that we hold the SMS role we are also responsible for threading our own
                // outgoing message, so it shows up in the real conversation.
                runCatching { SmsRepository(context).logSent(address, body, subscriptionId) }
                SendResult.Success
            }
            else -> SendResult.Failure(describe(resultCode))
        }
    }

    private fun defaultTimeoutFor(address: String): Long =
        if (PhoneAddress.isShortCode(address)) SHORT_CODE_TIMEOUT_MS else DIRECT_TIMEOUT_MS

    private fun smsManagerFor(context: Context, subscriptionId: Int): SmsManager? {
        val base = context.getSystemService(SmsManager::class.java) ?: return null
        return if (subscriptionId >= 0) {
            runCatching { base.createForSubscriptionId(subscriptionId) }.getOrDefault(base)
        } else {
            base
        }
    }

    private const val FAILED_TO_DISPATCH = -99

    private fun describe(resultCode: Int): String = when (resultCode) {
        FAILED_TO_DISPATCH -> "The system refused the send request"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic radio failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "The radio is off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Empty message"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No carrier service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED ->
            "Android's outgoing SMS limit was hit - increase the delay between replies"
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "Blocked by fixed dialling numbers"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED ->
            "Sending to this short code needs your confirmation"
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
            "This short code is blocked on your device"
        SmsManager.RESULT_RADIO_NOT_AVAILABLE -> "Radio unavailable"
        else -> "Send failed (code $resultCode)"
    }
}
