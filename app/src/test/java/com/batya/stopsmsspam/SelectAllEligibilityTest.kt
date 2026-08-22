package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.OptOutStatus
import com.batya.stopsmsspam.data.PhoneAddress
import com.batya.stopsmsspam.data.SenderGrouping
import com.batya.stopsmsspam.data.model.SpamMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which senders "Select all" is allowed to sweep up. */
class SelectAllEligibilityTest {

    private fun sender(address: String, body: String, optedOut: OptOutStatus? = null) =
        SenderGrouping.group(
            listOf(
                SpamMessage(
                    id = 1L,
                    address = address,
                    body = body,
                    date = 1L,
                    threadId = 1L,
                    subscriptionId = 1,
                ),
            ),
            "STOP",
            optedOut?.let { mapOf(PhoneAddress.normalize(address) to it) } ?: emptyMap(),
        ).single()

    @Test
    fun `a sender that offered an opt-out is included`() {
        assertTrue(sender("22395", "SALE! Reply STOP to opt out").includedInSelectAll)
    }

    // The case the exclusion exists for: replying tells a number that never asked for a keyword
    // that the line is live, so it should take a deliberate tap.
    @Test
    fun `a sender that never offered one is skipped`() {
        assertFalse(sender("18885551234", "your package is delayed, click here").includedInSelectAll)
    }

    // Still included, because nothing is sent to it - the batch only clears the thread. This is
    // the distinction that keeps the exclusion from quietly re-breaking bulk clearing.
    @Test
    fun `an already-opted-out sender is included even with no opt-out language`() {
        val status = OptOutStatus(keyword = "STOP", sentAtMillis = 1L, confirmedAtMillis = 2L)

        assertTrue(sender("18885551234", "click here", status).includedInSelectAll)
    }
}
