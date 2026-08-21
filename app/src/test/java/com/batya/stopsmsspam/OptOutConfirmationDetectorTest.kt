package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.OptOutConfirmationDetector
import com.batya.stopsmsspam.data.OptOutKeywordDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptOutConfirmationDetectorTest {

    @Test
    fun `recognises a senders acknowledgement`() {
        assertTrue(OptOutConfirmationDetector.isConfirmation("You have been unsubscribed from ACME alerts."))
        assertTrue(OptOutConfirmationDetector.isConfirmation("UNSUBSCRIBED. No more messages."))
        assertTrue(OptOutConfirmationDetector.isConfirmation("You have been removed from this list"))
        assertTrue(OptOutConfirmationDetector.isConfirmation("You have opted out and will get no more texts"))
        assertTrue(OptOutConfirmationDetector.isConfirmation("You will no longer receive messages from us"))
    }

    @Test
    fun `does not mistake the original solicitation for its own confirmation`() {
        // The whole distinction is tense: spam invites the action, the acknowledgement reports
        // it. Matching "unsubscribe" instead of "unsubscribed" would make every offer confirm
        // itself, and senders would look opted-out before anything was sent.
        assertFalse(OptOutConfirmationDetector.isConfirmation("FLASH SALE! Reply STOP to unsubscribe"))
        assertFalse(OptOutConfirmationDetector.isConfirmation("Text QUIT to unsubscribe at any time"))
        assertFalse(OptOutConfirmationDetector.isConfirmation("Reply STOP to opt out"))
        assertFalse(OptOutConfirmationDetector.isConfirmation(""))
    }

    @Test
    fun `recognises an outgoing opt-out reply in the sent box`() {
        // This is how an opt-out sent from the user's normal messaging app counts too.
        assertTrue(OptOutKeywordDetector.isOptOutReply("STOP"))
        assertTrue(OptOutKeywordDetector.isOptOutReply("  UNSUB  "))
        assertTrue(OptOutKeywordDetector.isOptOutReply("stop"))
        assertTrue(OptOutKeywordDetector.isOptOutReply("STOP2STOP"))
    }

    @Test
    fun `an ordinary outgoing message is not an opt-out reply`() {
        // A single bare keyword is the whole convention; anything conversational is not one.
        assertFalse(OptOutKeywordDetector.isOptOutReply("did the STOP work?"))
        assertFalse(OptOutKeywordDetector.isOptOutReply("Reply STOP to opt out"))
        assertFalse(OptOutKeywordDetector.isOptOutReply("see you tonight"))
        assertFalse(OptOutKeywordDetector.isOptOutReply(""))
    }
}
