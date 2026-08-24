package com.batya.stopsmsspam.data

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps the system blocked-numbers list.
 *
 * Writing to it is restricted to the default SMS app, the default dialer, and carrier apps -
 * which this app qualifies for exactly while it holds the SMS role. That makes blocking the
 * natural end of the line for a sender that confirmed an opt-out and then texted anyway: asking
 * politely already failed, and the app is, for the moment, in a position to enforce it.
 *
 * Blocking is system-wide and outlives this app, so it is only ever offered as an explicit
 * per-sender action, never rolled into a batch.
 */
class BlockedNumbers(private val context: Context) {

    fun canBlock(): Boolean =
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    suspend fun isBlocked(number: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)
    }

    /**
     * The subset of [numbers] already on the blocked list.
     *
     * Reading is subject to the same caller restriction as writing, so a lost SMS role turns this
     * into an empty set rather than an error - which reads as "nothing is blocked". That is the
     * safe direction here: the worst case is showing senders that could have been hidden, and
     * this filter is about noise, not protection. It is the opposite trade from the contacts
     * filter, where failing open had to be announced.
     *
     * One query per *distinct* number, and only for senders that survived the earlier filters.
     */
    suspend fun blockedAmong(numbers: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        if (!canBlock()) return@withContext emptySet()
        numbers.distinct()
            .filter { it.isNotBlank() }
            .filter { runCatching { BlockedNumberContract.isBlocked(context, it) }.getOrDefault(false) }
            .toSet()
    }

    /** @return true if the number is blocked afterwards, whether or not this call did it. */
    suspend fun block(number: String): Boolean = withContext(Dispatchers.IO) {
        if (number.isBlank()) return@withContext false
        runCatching {
            if (BlockedNumberContract.isBlocked(context, number)) return@runCatching true
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values,
            ) != null
        }.getOrDefault(false)
    }
}
