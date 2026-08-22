package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.OptOutStatus
import com.batya.stopsmsspam.data.RememberedConfirmations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedConfirmationsTest {

    private fun derived(vararg entries: Pair<String, OptOutStatus>) = mapOf(*entries)

    @Test
    fun `restores a confirmation whose message was deleted on arrival`() {
        // Without this the sender would silently revert to "opt-out sent, no confirmation", and
        // a later violation would never escalate to STOP IGNORED.
        val result = RememberedConfirmations.applyTo(
            derived("22395" to OptOutStatus("STOP", sentAtMillis = 100L)),
            remembered = mapOf("22395" to 200L),
        )

        assertTrue(result.getValue("22395").isConfirmed)
        assertEquals(200L, result.getValue("22395").confirmedAtMillis)
    }

    @Test
    fun `a remembered confirmation older than the opt-out does not count`() {
        // A sender told to stop a second time has not answered the second request just because
        // it answered the first.
        val result = RememberedConfirmations.applyTo(
            derived("22395" to OptOutStatus("STOP", sentAtMillis = 500L)),
            remembered = mapOf("22395" to 200L),
        )

        assertFalse(result.getValue("22395").isConfirmed)
        assertNull(result.getValue("22395").confirmedAtMillis)
    }

    @Test
    fun `a confirmation still in the provider is left alone`() {
        val provider = OptOutStatus("STOP", sentAtMillis = 100L, confirmedAtMillis = 150L)
        val result = RememberedConfirmations.applyTo(
            derived("22395" to provider),
            remembered = mapOf("22395" to 900L),
        )

        assertEquals(150L, result.getValue("22395").confirmedAtMillis)
    }

    @Test
    fun `senders with nothing remembered are untouched`() {
        val derived = derived(
            "22395" to OptOutStatus("STOP", sentAtMillis = 100L),
            "43733" to OptOutStatus("UNSUB", sentAtMillis = 100L),
        )
        assertEquals(derived, RememberedConfirmations.applyTo(derived, emptyMap()))
        assertEquals(derived, RememberedConfirmations.applyTo(derived, mapOf("55411" to 999L)))
    }
}
