package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.OptOutStatus
import com.batya.stopsmsspam.data.RememberedConfirmations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seeding rule that keeps a deleted thread from erasing the fact it was replied to.
 *
 * `loadOptOutStatus` needs a provider to run, so what is pinned here is the pure arithmetic it
 * performs on the seeded map: which of two records for the same sender wins, and that a
 * remembered confirmation still attaches once the Sent row backing it is gone.
 */
class RememberedOptOutsTest {

    private fun sent(at: Long, keyword: String = "STOP") =
        OptOutStatus(keyword = keyword, sentAtMillis = at)

    // The Sent-box pass overwrites the seed only when its row is at least as new. A surviving
    // row is the better record - it carries the provider's own timestamp - but a remembered
    // opt-out from a later batch must not be rolled back to an older one.
    @Test
    fun `the newer of a remembered and a provider opt-out wins`() {
        val remembered = sent(2_000L, "END")
        val fromProvider = sent(1_000L, "STOP")

        val winner = if (fromProvider.sentAtMillis < remembered.sentAtMillis) remembered else fromProvider

        assertEquals("END", winner.keyword)
        assertEquals(2_000L, winner.sentAtMillis)
    }

    @Test
    fun `a provider row newer than the memory replaces it`() {
        val remembered = sent(1_000L, "END")
        val fromProvider = sent(5_000L, "STOP")

        val winner = if (fromProvider.sentAtMillis < remembered.sentAtMillis) remembered else fromProvider

        assertEquals("STOP", winner.keyword)
        assertEquals(5_000L, winner.sentAtMillis)
    }

    // The whole point of seeding before the Sent-box pass: with the row deleted there is nothing
    // for a confirmation to attach to unless the remembered opt-out is already in the map.
    @Test
    fun `a confirmation still attaches to a remembered opt-out`() {
        val seeded = mapOf("15551234567" to sent(1_000L))

        val merged = RememberedConfirmations.applyTo(seeded, mapOf("15551234567" to 2_000L))

        assertTrue(merged.getValue("15551234567").isConfirmed)
        assertEquals(2_000L, merged.getValue("15551234567").confirmedAtMillis)
    }

    // Unchanged rule, restated against a remembered opt-out: a reply that predates the request
    // cannot be answering it.
    @Test
    fun `a confirmation predating the remembered opt-out is ignored`() {
        val seeded = mapOf("15551234567" to sent(5_000L))

        val merged = RememberedConfirmations.applyTo(seeded, mapOf("15551234567" to 1_000L))

        assertNull(merged.getValue("15551234567").confirmedAtMillis)
    }
}
