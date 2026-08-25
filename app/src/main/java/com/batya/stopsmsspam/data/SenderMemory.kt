package com.batya.stopsmsspam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.senderMemoryDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "sender_memory")

/**
 * The little the app has to remember because it deletes the evidence.
 *
 * Opt-out state is otherwise derived from the message history and nothing is stored - see
 * `SmsRepository.loadOptOutStatus`. This store exists for the one case where that is impossible:
 * the user asked for a sender's confirmation reply to be deleted on arrival, and the derivation
 * reads exactly that reply to know the sender ever acknowledged anything.
 *
 * So this is not a second source of truth competing with the provider. It holds the facts the
 * provider can no longer answer *because the user asked us to destroy them*: which senders'
 * replies to drop, when they confirmed, and - since the batch deletes the thread it just
 * replied in - that an opt-out was sent at all.
 */
class SenderMemory(private val context: Context) {

    /** Records that this sender's thread was deleted, so its reply should be dropped too. */
    suspend fun markAutoDeleteResponses(address: String) {
        val key = PhoneAddress.normalize(address)
        if (key.isEmpty()) return
        edit { memos ->
            memos[key] = (memos[key] ?: Memo()).copy(autoDeleteResponses = true)
        }
    }

    /**
     * Remembers that the sender acknowledged the opt-out, called immediately before the message
     * carrying that acknowledgement is dropped. Recorded once - a later confirmation does not
     * move the timestamp, since the first one is what a violation has to postdate.
     */
    suspend fun rememberConfirmation(address: String, atMillis: Long) {
        val key = PhoneAddress.normalize(address)
        if (key.isEmpty()) return
        edit { memos ->
            val existing = memos[key] ?: Memo()
            if (existing.confirmedAtMillis == null) {
                memos[key] = existing.copy(confirmedAtMillis = atMillis)
            }
        }
    }

    /**
     * Remembers that an opt-out went out, called before the message carrying it is deleted.
     *
     * Overwrites, where [rememberConfirmation] keeps the first: the derivation this feeds uses
     * "latest opt-out wins", because a sender told to stop twice must be answered by a
     * confirmation postdating the *second* attempt, not the first.
     */
    suspend fun rememberOptOutSent(address: String, keyword: String, atMillis: Long) {
        val key = PhoneAddress.normalize(address)
        if (key.isEmpty() || keyword.isBlank()) return
        edit { memos ->
            val existing = memos[key] ?: Memo()
            if ((existing.optOutSentAtMillis ?: 0L) <= atMillis) {
                memos[key] = existing.copy(
                    optOutKeyword = keyword.trim().uppercase(),
                    optOutSentAtMillis = atMillis,
                )
            }
        }
    }

    /**
     * Opt-outs whose Sent-box row no longer exists, by normalized address.
     *
     * Never carries a confirmation: these describe only *that we asked*. Whether the sender ever
     * answered is still derived from the inbox, or from [rememberedConfirmations] when that reply
     * was deleted too.
     */
    suspend fun rememberedOptOuts(): Map<String, OptOutStatus> =
        read().mapNotNull { (address, memo) ->
            val at = memo.optOutSentAtMillis ?: return@mapNotNull null
            val keyword = memo.optOutKeyword ?: return@mapNotNull null
            address to OptOutStatus(keyword = keyword, sentAtMillis = at)
        }.toMap()

    suspend fun shouldAutoDeleteResponses(address: String): Boolean {
        val key = PhoneAddress.normalize(address)
        return read()[key]?.autoDeleteResponses == true
    }

    /** Confirmations whose original message no longer exists, by normalized address. */
    suspend fun rememberedConfirmations(): Map<String, Long> =
        read().mapNotNull { (address, memo) -> memo.confirmedAtMillis?.let { address to it } }
            .toMap()

    private data class Memo(
        val autoDeleteResponses: Boolean = false,
        val confirmedAtMillis: Long? = null,
        val optOutKeyword: String? = null,
        val optOutSentAtMillis: Long? = null,
    )

    private suspend fun read(): Map<String, Memo> =
        decode(context.senderMemoryDataStore.data.first()[KEY])

    private suspend fun edit(transform: (MutableMap<String, Memo>) -> Unit) {
        context.senderMemoryDataStore.edit { prefs ->
            val memos = decode(prefs[KEY]).toMutableMap()
            transform(memos)
            prefs[KEY] = encode(memos)
        }
    }

    private fun encode(memos: Map<String, Memo>): String =
        JSONObject().apply {
            memos.forEach { (address, memo) ->
                put(
                    address,
                    JSONObject()
                        .put(FIELD_AUTO_DELETE, memo.autoDeleteResponses)
                        .put(FIELD_CONFIRMED, memo.confirmedAtMillis ?: JSONObject.NULL)
                        .put(FIELD_OPT_OUT_KEYWORD, memo.optOutKeyword ?: JSONObject.NULL)
                        .put(FIELD_OPT_OUT_SENT, memo.optOutSentAtMillis ?: JSONObject.NULL),
                )
            }
        }.toString()

    private fun decode(raw: String?): Map<String, Memo> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { address ->
                val entry = json.getJSONObject(address)
                Memo(
                    autoDeleteResponses = entry.optBoolean(FIELD_AUTO_DELETE),
                    confirmedAtMillis = entry.optLong(FIELD_CONFIRMED).takeIf { it > 0 },
                    optOutKeyword = entry.optString(FIELD_OPT_OUT_KEYWORD)
                        .takeIf { it.isNotEmpty() && it != "null" },
                    optOutSentAtMillis = entry.optLong(FIELD_OPT_OUT_SENT).takeIf { it > 0 },
                )
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY = stringPreferencesKey("sender_memos")
        const val FIELD_AUTO_DELETE = "ad"
        const val FIELD_CONFIRMED = "c"
        const val FIELD_OPT_OUT_KEYWORD = "ok"
        const val FIELD_OPT_OUT_SENT = "os"
    }
}
