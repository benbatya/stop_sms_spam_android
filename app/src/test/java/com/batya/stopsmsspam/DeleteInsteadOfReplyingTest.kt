package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.OptOutStatus
import com.batya.stopsmsspam.data.PhoneAddress
import com.batya.stopsmsspam.data.SenderGrouping
import com.batya.stopsmsspam.data.model.SpamMessage
import com.batya.stopsmsspam.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Review screen's "delete instead of replying" choice, as the state sees it.
 *
 * What is being pinned down is that suppressing a reply moves a sender between the two lists the
 * rest of the app counts off - the pacing estimate, the throttle warning, the confirmation
 * dialog and the send button all read `selectedForReply`/`selectedForCleanup` - while keeping
 * "the user chose this" distinguishable from "there was nobody left to write to".
 */
class DeleteInsteadOfReplyingTest {

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

    private fun state(vararg senders: com.batya.stopsmsspam.data.model.SpamSender) =
        UiState(
            senders = senders.toList(),
            selected = senders.map { it.normalizedAddress }.toSet(),
        )

    @Test
    fun `a selected sender is replied to by default`() {
        val spam = sender("22395", "SALE! Reply STOP to opt out")
        val state = state(spam)

        assertTrue(state.sendsReply(spam))
        assertEquals(listOf(spam), state.selectedForReply)
        assertTrue(state.selectedForCleanup.isEmpty())
    }

    @Test
    fun `suppressing the reply moves the sender out of the send list`() {
        val spam = sender("22395", "SALE! Reply STOP to opt out")
        val state = state(spam).copy(replySuppressed = setOf(spam.normalizedAddress))

        assertFalse(state.sendsReply(spam))
        assertTrue(state.selectedForReply.isEmpty())
        assertEquals(listOf(spam), state.selectedForCleanup)
    }

    // The reason the two cleanup lists exist separately: one is something the sender did, the
    // other is the user's own decision, and only the second is theirs to take back.
    @Test
    fun `chosen and already-opted-out are cleared for different reasons`() {
        val chosen = sender("22395", "SALE! Reply STOP to opt out")
        val done = sender(
            "44556",
            "you are unsubscribed",
            OptOutStatus(keyword = "STOP", sentAtMillis = 1L, confirmedAtMillis = 2L),
        )
        val state = state(chosen, done).copy(replySuppressed = setOf(chosen.normalizedAddress))

        assertEquals(listOf(chosen), state.selectedDeleteOnly)
        assertEquals(listOf(done), state.selectedAlreadyOptedOut)
        assertEquals(2, state.selectedForCleanup.size)
    }

    // A sender already opted out was never going to be texted, so suppressing its reply must not
    // report it as a choice the user made - it would put their name on the app's own decision.
    @Test
    fun `suppressing an already-opted-out sender changes nothing`() {
        val done = sender(
            "44556",
            "you are unsubscribed",
            OptOutStatus(keyword = "STOP", sentAtMillis = 1L, confirmedAtMillis = 2L),
        )
        val state = state(done).copy(replySuppressed = setOf(done.normalizedAddress))

        assertTrue(state.selectedDeleteOnly.isEmpty())
        assertEquals(listOf(done), state.selectedAlreadyOptedOut)
    }

    // The field has to be clearable to be retypeable, so an empty one is a state the user can
    // reach in one tap - and a blank SMS to a spammer is worse than the keyword we guessed.
    @Test
    fun `an emptied keyword field falls back to the detected keyword`() {
        val spam = sender("22395", "SALE! Reply STOP to opt out")
        val state = state(spam).copy(keywordOverrides = mapOf(spam.normalizedAddress to "   "))

        assertEquals("", state.keywordFor(spam).trim())
        assertEquals("STOP", state.outgoingKeyword(spam))
    }

    @Test
    fun `an edited keyword is what goes out`() {
        val spam = sender("22395", "SALE! Reply STOP to opt out")
        val state = state(spam).copy(keywordOverrides = mapOf(spam.normalizedAddress to "UNSUB"))

        assertEquals("UNSUB", state.outgoingKeyword(spam))
    }

    // Nothing leaves the phone for a suppressed sender, so it must not be paced or counted
    // towards the framework's burst limit either.
    @Test
    fun `a suppressed sender does not count towards the send total`() {
        val senders = (0 until 5).map { sender("2239$it", "SALE! Reply STOP to opt out") }
        val state = state(*senders.toTypedArray())
            .copy(replySuppressed = senders.take(3).map { it.normalizedAddress }.toSet())

        assertEquals(2, state.selectedForReply.size)
        assertEquals(5, state.selectedSenders.size)
    }
}
