package com.batya.stopsmsspam.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.batya.stopsmsspam.data.model.SpamMessage
import com.batya.stopsmsspam.data.model.SpamSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes the system SMS provider. The provider is the single source of truth - this
 * app keeps no message database of its own, so anything it marks read or files into the Sent box
 * is immediately visible to whatever messaging app the user goes back to.
 */
class SmsRepository(private val context: Context) {

    fun canReadSms(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every unread inbox message, collapsed to one row per sender so a number that texted eight
     * times receives exactly one opt-out reply.
     */
    suspend fun loadUnreadSenders(fallbackKeyword: String): List<SpamSender> =
        withContext(Dispatchers.IO) {
            if (!canReadSms()) return@withContext emptyList()

            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.SUBSCRIPTION_ID,
            )

            // Newest first, so the first row seen for a sender is the one we quote and reply to.
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.READ} = 0",
                null,
                "${Telephony.Sms.DATE} DESC",
            ) ?: return@withContext emptyList()

            val messages = mutableListOf<SpamMessage>()
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val threadIdx = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val subIdx = it.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)

                while (it.moveToNext()) {
                    messages += SpamMessage(
                        id = it.getLong(idIdx),
                        address = it.getString(addressIdx) ?: continue,
                        body = it.getString(bodyIdx).orEmpty(),
                        date = it.getLong(dateIdx),
                        threadId = it.getLong(threadIdx),
                        subscriptionId = it.getInt(subIdx),
                    )
                }
            }

            SenderGrouping.group(messages, fallbackKeyword)
        }

    /**
     * Stores a message the system handed us via SMS_DELIVER. Only the default SMS app may do
     * this, and while we are the default app nothing else will.
     */
    suspend fun insertIncoming(
        address: String,
        body: String,
        sentAtMillis: Long,
        subscriptionId: Int,
    ): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentAtMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    /** Files a reply we sent into the Sent box so it appears in the real conversation thread. */
    suspend fun logSent(address: String, body: String, subscriptionId: Int): Uri? =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        }

    suspend fun markRead(messageIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext 0
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        context.contentResolver.update(
            Telephony.Sms.CONTENT_URI,
            values,
            idSelection(messageIds),
            null,
        )
    }

    suspend fun delete(messageIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext 0
        context.contentResolver.delete(Telephony.Sms.CONTENT_URI, idSelection(messageIds), null)
    }

    /** Ids come from the provider itself, so inlining them cannot smuggle in SQL. */
    private fun idSelection(ids: List<Long>) =
        "${Telephony.Sms._ID} IN (${ids.joinToString(",")})"

}
