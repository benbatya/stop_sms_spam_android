package com.batya.stopsmsspam.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.batya.stopsmsspam.data.model.MessageRef
import com.batya.stopsmsspam.data.model.MessageSource
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
    suspend fun loadUnreadSenders(
        fallbackKeyword: String,
        optedOut: Map<String, OptOutStatus> = emptyMap(),
    ): List<SpamSender> =
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

            val all = (messages + runCatching { loadUnreadMms() }.getOrDefault(emptyList()))
                .sortedByDescending { it.date }
            SenderGrouping.group(all, fallbackKeyword, optedOut)
        }

    /**
     * Unread inbox MMS, shaped like the SMS rows so both go through the same grouping.
     *
     * Four bulk queries and no per-message work. The obvious route - reading each message's
     * sender from `content://mms/<id>/addr` - is per-message only; a bulk query against that
     * table returns nothing, which on a real inbox would mean over a thousand round trips. The
     * thread's recipient resolves the same address in one pass instead.
     */
    private fun loadUnreadMms(): List<SpamMessage> {
        val threadAddresses = threadAddresses()
        if (threadAddresses.isEmpty()) return emptyList()
        val bodies = mmsTextBodies()

        val cursor = context.contentResolver.query(
            Telephony.Mms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
            "${Telephony.Mms.READ} = 0",
            null,
            "${Telephony.Mms.DATE} DESC",
        ) ?: return emptyList()

        return cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Mms._ID)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
            buildList {
                while (it.moveToNext()) {
                    val threadId = it.getLong(threadIdx)
                    // Absent for a group thread, which is skipped on purpose: it has no single
                    // spam sender, and replying STOP into one would text strangers.
                    val address = threadAddresses[threadId] ?: continue
                    val id = it.getLong(idIdx)
                    add(
                        SpamMessage(
                            id = id,
                            address = address,
                            body = bodies[id].orEmpty(),
                            // MMS stores seconds where SMS stores milliseconds. Without this
                            // every MMS sorts to 1970 and shows a 1970 date.
                            date = it.getLong(dateIdx) * 1000L,
                            threadId = threadId,
                            subscriptionId = -1,
                            source = MessageSource.MMS,
                        ),
                    )
                }
            }
        }
    }

    /** thread id -> the single other party, for one-to-one threads only. */
    private fun threadAddresses(): Map<Long, String> {
        val recipients = HashMap<Long, String>()
        context.contentResolver.query(
            Uri.parse("content://mms-sms/canonical-addresses"),
            arrayOf("_id", "address"),
            null,
            null,
            null,
        )?.use {
            while (it.moveToNext()) recipients[it.getLong(0)] = it.getString(1).orEmpty()
        }
        if (recipients.isEmpty()) return emptyMap()

        val threads = HashMap<Long, String>()
        context.contentResolver.query(
            Uri.parse("content://mms-sms/conversations?simple=true"),
            arrayOf("_id", "recipient_ids"),
            null,
            null,
            null,
        )?.use {
            while (it.moveToNext()) {
                val ids = it.getString(1).orEmpty().trim().split(" ").filter { id -> id.isNotBlank() }
                if (ids.size != 1) continue
                val address = recipients[ids.single().toLongOrNull() ?: continue] ?: continue
                // RCS and email-gateway participants come through this table too, as
                // "...@rcs.google.com". They are not SMS-addressable: an opt-out sent there
                // would fail, and a number cannot be blocked that has no number. Listing them
                // would fill the inbox with senders nothing in this app can act on.
                if (address.isNotBlank() && '@' !in address) threads[it.getLong(0)] = address
            }
        }
        return threads
    }

    /** message id -> its text/plain part, which is what the keyword detector reads. */
    private fun mmsTextBodies(): Map<Long, String> {
        val bodies = HashMap<Long, String>()
        context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("mid", "text"),
            "ct = ?",
            arrayOf("text/plain"),
            null,
        )?.use {
            while (it.moveToNext()) {
                val text = it.getString(1) ?: continue
                val mid = it.getLong(0)
                // A message can have several text parts; the first is the body proper.
                if (mid !in bodies) bodies[mid] = text
            }
        }
        return bodies
    }

    /**
     * Works out, from the message history alone, which senders have already been told to stop
     * and which of those said so back.
     *
     * Two passes over the provider:
     *  1. the Sent box, for outgoing messages that *are* an opt-out keyword - this is what makes
     *     an opt-out sent from the user's normal messaging app count exactly as much as one this
     *     app sent;
     *  2. the inbox from the earliest of those onwards, for the sender's acknowledgement.
     *
     * The second query is bounded by the first: with no opt-outs sent there is nothing to look
     * for, and otherwise only messages newer than the earliest opt-out can possibly confirm one.
     */
    suspend fun loadOptOutStatus(
        rememberedConfirmations: Map<String, Long> = emptyMap(),
    ): Map<String, OptOutStatus> = withContext(Dispatchers.IO) {
        if (!canReadSms()) return@withContext emptyMap()

        val sent = context.contentResolver.query(
            Telephony.Sms.Sent.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} ASC",
        ) ?: return@withContext emptyMap()

        // Latest opt-out wins: if a sender was told to stop twice, the second attempt is the one
        // a confirmation has to follow.
        val optOuts = LinkedHashMap<String, OptOutStatus>()
        sent.use {
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val body = it.getString(bodyIdx).orEmpty()
                if (!OptOutKeywordDetector.isOptOutReply(body)) continue
                val key = PhoneAddress.normalize(it.getString(addressIdx) ?: continue)
                if (key.isEmpty()) continue
                optOuts[key] = OptOutStatus(
                    keyword = body.trim().uppercase(),
                    sentAtMillis = it.getLong(dateIdx),
                )
            }
        }
        if (optOuts.isEmpty()) return@withContext emptyMap()

        val earliest = optOuts.values.minOf { it.sentAtMillis }
        val replies = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(earliest.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return@withContext RememberedConfirmations.applyTo(optOuts, rememberedConfirmations)

        replies.use {
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val key = PhoneAddress.normalize(it.getString(addressIdx) ?: continue)
                val pending = optOuts[key] ?: continue
                if (pending.isConfirmed) continue

                val date = it.getLong(dateIdx)
                // Must postdate the opt-out: otherwise a solicitation could confirm itself.
                if (date <= pending.sentAtMillis) continue
                if (!OptOutConfirmationDetector.isConfirmation(it.getString(bodyIdx).orEmpty())) continue

                optOuts[key] = pending.copy(confirmedAtMillis = date)
            }
        }
        // Confirmations whose message was deleted on arrival are folded back in here: the
        // provider cannot answer for evidence the app was asked to destroy.
        RememberedConfirmations.applyTo(optOuts, rememberedConfirmations)
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

    suspend fun markRead(messages: List<MessageRef>): Int = withContext(Dispatchers.IO) {
        eachSource(messages) { uri, ids ->
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.update(uri, values, idSelection(ids), null)
        }
    }

    suspend fun delete(messages: List<MessageRef>): Int = withContext(Dispatchers.IO) {
        eachSource(messages) { uri, ids ->
            context.contentResolver.delete(uri, idSelection(ids), null)
        }
    }

    /**
     * Applies [action] once per provider table. SMS and MMS are separate stores with separate id
     * spaces, so a single id list would silently address the wrong rows in one of them.
     */
    private inline fun eachSource(
        messages: List<MessageRef>,
        action: (uri: Uri, ids: List<Long>) -> Int,
    ): Int {
        if (messages.isEmpty()) return 0
        return messages.groupBy { it.source }.entries.sumOf { (source, refs) ->
            val uri = when (source) {
                MessageSource.SMS -> Telephony.Sms.CONTENT_URI
                MessageSource.MMS -> Telephony.Mms.CONTENT_URI
            }
            runCatching { action(uri, refs.map { it.id }) }.getOrDefault(0)
        }
    }

    /** Ids come from the provider itself, so inlining them cannot smuggle in SQL. */
    private fun idSelection(ids: List<Long>) =
        "${Telephony.Sms._ID} IN (${ids.joinToString(",")})"

}
