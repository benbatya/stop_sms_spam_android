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
