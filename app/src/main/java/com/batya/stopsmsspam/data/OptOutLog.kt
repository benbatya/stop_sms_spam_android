package com.batya.stopsmsspam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** What we sent a sender, and when the radio confirmed it. */
data class OptOutRecord(
    val keyword: String,
    val timestampMillis: Long,
)

private val Context.optOutDataStore: DataStore<Preferences> by preferencesDataStore(name = "optouts")

/**
 * Remembers which senders have already been sent an opt-out.
 *
 * Needed because opting out is not the end of the conversation: a sender that honours STOP
 * usually replies to confirm it ("You have been unsubscribed"), and that confirmation arrives as
 * a new unread message from the same address. Without a record, the app would list that sender
 * as fresh spam and offer to text them again - which is noise at best, and at worst re-opens a
 * conversation the user just closed.
 *
 * Only **confirmed** sends are recorded. A dry run does not write here, and neither does an
 * UNCONFIRMED send: if Android never told us the message went out, claiming the sender is
 * unsubscribed would be a guess, and the wrong direction to guess in.
 */
class OptOutLog(private val context: Context) {

    /** Keyed by [PhoneAddress.normalize], so a sender is recognised across address formats. */
    val records: Flow<Map<String, OptOutRecord>> =
        context.optOutDataStore.data.map { decode(it[KEY]) }

    suspend fun current(): Map<String, OptOutRecord> = records.first()

    suspend fun record(address: String, keyword: String, timestampMillis: Long) {
        val key = PhoneAddress.normalize(address)
        if (key.isEmpty()) return
        context.optOutDataStore.edit { prefs ->
            val updated = decode(prefs[KEY]).toMutableMap()
            updated[key] = OptOutRecord(keyword, timestampMillis)
            prefs[KEY] = encode(updated)
        }
    }

    /**
     * Drops a sender's record, so the app will offer to opt out again. For the case where a
     * sender keeps texting after being told to stop and the user wants another go.
     */
    suspend fun forget(address: String) {
        val key = PhoneAddress.normalize(address)
        context.optOutDataStore.edit { prefs ->
            val updated = decode(prefs[KEY]).toMutableMap()
            updated.remove(key)
            prefs[KEY] = encode(updated)
        }
    }

    private fun encode(records: Map<String, OptOutRecord>): String =
        JSONObject().apply {
            records.forEach { (address, record) ->
                put(
                    address,
                    JSONObject()
                        .put(FIELD_KEYWORD, record.keyword)
                        .put(FIELD_TIMESTAMP, record.timestampMillis),
                )
            }
        }.toString()

    private fun decode(raw: String?): Map<String, OptOutRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { address ->
                val entry = json.getJSONObject(address)
                OptOutRecord(
                    keyword = entry.optString(FIELD_KEYWORD, "STOP"),
                    timestampMillis = entry.optLong(FIELD_TIMESTAMP),
                )
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        val KEY = stringPreferencesKey("opted_out")
        const val FIELD_KEYWORD = "k"
        const val FIELD_TIMESTAMP = "t"
    }
}
