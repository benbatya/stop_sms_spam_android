package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.OptOutStatus
import com.batya.stopsmsspam.data.PhoneAddress
import com.batya.stopsmsspam.data.SenderGrouping
import com.batya.stopsmsspam.data.model.KeywordConfidence
import com.batya.stopsmsspam.data.model.MessageSource
import com.batya.stopsmsspam.data.model.SpamMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderGroupingTest {

    private fun message(
        id: Long,
        address: String,
        body: String = "Reply STOP to opt out",
        date: Long = id,
        source: MessageSource = MessageSource.SMS,
    ) = SpamMessage(
        id = id,
        address = address,
        body = body,
        date = date,
        threadId = 1L,
        subscriptionId = 1,
        source = source,
    )

    @Test
    fun `eight messages from one number become one reply`() {
        val messages = (1L..8L).map { message(it, "+15551234567", date = 100 - it) }

        val senders = SenderGrouping.group(messages, fallbackKeyword = "STOP")

        assertEquals(1, senders.size)
        assertEquals(8, senders.single().messageCount)
        assertEquals(8, senders.single().messages.size)
    }

    @Test
    fun `different formats of the same number group together`() {
        val messages = listOf(
            message(1, "+1 555-123-4567"),
            message(2, "5551234567"),
            message(3, "(555) 123-4567"),
        )

        assertEquals(1, SenderGrouping.group(messages, "STOP").size)
    }

    @Test
    fun `distinct senders stay distinct`() {
        val messages = listOf(
            message(1, "+15551234567"),
            message(2, "22395"),
            message(3, "AMZN"),
        )

        assertEquals(3, SenderGrouping.group(messages, "STOP").size)
    }

    @Test
    fun `keyword comes from the newest message of the sender`() {
        // Newest first, which is the order the provider query returns.
        val messages = listOf(
            message(9, "22395", body = "Final notice. Reply QUIT to cancel.", date = 999),
            message(8, "22395", body = "Deal! Reply STOP to opt out", date = 100),
        )

        val sender = SenderGrouping.group(messages, "STOP").single()

        assertEquals("QUIT", sender.keyword.keyword)
        assertEquals(2, sender.messageCount)
    }

    @Test
    fun `flags senders that never offered an opt-out`() {
        val messages = listOf(
            message(1, "22395", body = "Sale! Reply STOP to opt out"),
            message(2, "+15559999999", body = "your package is delayed, click bit.ly/x"),
        )

        val senders = SenderGrouping.group(messages, "STOP").associateBy { it.displayAddress }

        assertTrue(senders.getValue("22395").hasOptOutLanguage)
        assertFalse(senders.getValue("+15559999999").hasOptOutLanguage)
        assertEquals(
            KeywordConfidence.ASSUMED,
            senders.getValue("+15559999999").keyword.confidence,
        )
    }

    @Test
    fun `preserves newest-first ordering of senders`() {
        val messages = listOf(
            message(1, "11111111111", date = 300),
            message(2, "22222222222", date = 200),
            message(3, "33333333333", date = 100),
        )

        val order = SenderGrouping.group(messages, "STOP").map { it.latestDate }

        assertEquals(listOf(300L, 200L, 100L), order)
    }

    @Test
    fun `marks a sender unsubscribed once an opt-out has been sent to them`() {
        // The confirmation a sender sends back ("you have been unsubscribed") arrives as a new
        // unread message from the same address. Without the record it would look like fresh spam.
        val messages = listOf(
            message(1, "22395", body = "You have been unsubscribed from ACME alerts."),
            message(2, "43733", body = "Deals daily! Reply UNSUB to be removed"),
        )
        val log = mapOf(
            PhoneAddress.normalize("22395") to OptOutStatus("STOP", sentAtMillis = 1_700_000_000_000, confirmedAtMillis = 1_700_000_001_000),
        )

        val senders = SenderGrouping.group(messages, "STOP", log).associateBy { it.displayAddress }

        assertTrue(senders.getValue("22395").isUnsubscribed)
        assertFalse(senders.getValue("22395").canReply)
        assertEquals("STOP", senders.getValue("22395").optedOut?.keyword)

        assertFalse(senders.getValue("43733").isUnsubscribed)
        assertTrue(senders.getValue("43733").canReply)
    }

    @Test
    fun `recognises an unsubscribed sender across address formats`() {
        // The opt-out went to "+1 555-123-4567"; the confirmation comes back as "5551234567".
        val log = mapOf(
            PhoneAddress.normalize("+1 555-123-4567") to OptOutStatus("STOP", sentAtMillis = 1L, confirmedAtMillis = 2L),
        )
        val sender = SenderGrouping.group(
            listOf(message(1, "5551234567", body = "You are unsubscribed")),
            "STOP",
            log,
        ).single()

        assertTrue(sender.isUnsubscribed)
    }

    @Test
    fun `a sender that was sent an opt-out but never answered is awaiting confirmation`() {
        // Not the same as unsubscribed: we asked, they have not agreed. Still not repliable -
        // the ask already went out - but the UI must not claim the sender is done.
        val status = mapOf(
            PhoneAddress.normalize("22395") to OptOutStatus("STOP", sentAtMillis = 100L),
        )
        val sender = SenderGrouping.group(
            listOf(message(1, "22395", body = "Another sale! Reply STOP to opt out")),
            "STOP",
            status,
        ).single()

        assertFalse(sender.isUnsubscribed)
        assertTrue(sender.awaitingConfirmation)
        assertFalse(sender.canReply)
    }

    @Test
    fun `flags a sender that confirmed the opt-out and then texted again`() {
        val status = mapOf(
            PhoneAddress.normalize("22395") to
                OptOutStatus("STOP", sentAtMillis = 100L, confirmedAtMillis = 200L),
        )
        val messages = listOf(
            message(2, "22395", body = "FLASH SALE! 50% off today", date = 300L),
            message(1, "22395", body = "You have been unsubscribed", date = 200L),
        )

        val sender = SenderGrouping.group(messages, "STOP", status).single()

        assertTrue(sender.ignoredOptOut)
        assertEquals(300L, sender.optOutViolatedAt)
    }

    @Test
    fun `the confirmation itself is not a violation`() {
        val status = mapOf(
            PhoneAddress.normalize("22395") to
                OptOutStatus("STOP", sentAtMillis = 100L, confirmedAtMillis = 200L),
        )
        // A sender that sends only the acknowledgement, and a chatty one that repeats it, are
        // both keeping their word - neither should be accused of ignoring the opt-out.
        val messages = listOf(
            message(2, "22395", body = "You have been unsubscribed. Goodbye.", date = 400L),
            message(1, "22395", body = "You have been unsubscribed", date = 200L),
        )

        val sender = SenderGrouping.group(messages, "STOP", status).single()

        assertFalse(sender.ignoredOptOut)
    }

    @Test
    fun `an unconfirmed opt-out cannot be violated`() {
        // No acknowledgement means no promise was made. A sender that never answered and keeps
        // texting is unhelpful, but calling that a broken promise would be wrong.
        val status = mapOf(
            PhoneAddress.normalize("22395") to OptOutStatus("STOP", sentAtMillis = 100L),
        )
        val sender = SenderGrouping.group(
            listOf(message(1, "22395", body = "FLASH SALE!", date = 900L)),
            "STOP",
            status,
        ).single()

        assertFalse(sender.ignoredOptOut)
        assertTrue(sender.awaitingConfirmation)
    }

    @Test
    fun `an empty log leaves every sender repliable`() {
        val senders = SenderGrouping.group(
            listOf(message(1, "22395"), message(2, "43733")),
            "STOP",
        )
        assertTrue(senders.all { it.canReply })
        assertTrue(senders.none { it.isUnsubscribed })
    }

    @Test
    fun `groups SMS and MMS from the same sender into one row`() {
        // The whole point of surfacing MMS: a sender that used both is one sender, and gets one
        // reply - not one per table.
        val messages = listOf(
            message(1, "+18022160869", date = 300L, source = MessageSource.MMS),
            message(2, "8022160869", date = 200L),
        )

        val sender = SenderGrouping.group(messages, "STOP").single()

        assertEquals(2, sender.messageCount)
        assertTrue(sender.hasMms)
        assertEquals(
            listOf(MessageSource.MMS, MessageSource.SMS),
            sender.messages.map { it.source },
        )
    }

    @Test
    fun `an all-SMS sender is not flagged as having MMS`() {
        assertFalse(SenderGrouping.group(listOf(message(1, "22395")), "STOP").single().hasMms)
    }

    // The badge says "MMS" or "SMS + MMS" off this flag alone, so getting it backwards would
    // mislabel every mixed thread in the list.
    @Test
    fun `a mixed thread is not all-MMS`() {
        val messages = listOf(
            message(1, "8022160869", date = 300L, source = MessageSource.MMS),
            message(2, "8022160869", date = 200L),
        )

        val sender = SenderGrouping.group(messages, "STOP").single()

        assertTrue(sender.hasMms)
        assertFalse(sender.isAllMms)
        assertEquals(1, sender.mmsCount)
    }

    @Test
    fun `a thread of nothing but MMS is all-MMS`() {
        val messages = listOf(
            message(1, "8022160869", date = 300L, source = MessageSource.MMS),
            message(2, "8022160869", date = 200L, source = MessageSource.MMS),
        )

        val sender = SenderGrouping.group(messages, "STOP").single()

        assertTrue(sender.isAllMms)
        assertEquals(2, sender.mmsCount)
    }

    @Test
    fun `an all-SMS sender is not all-MMS`() {
        val sender = SenderGrouping.group(listOf(message(1, "22395")), "STOP").single()

        assertFalse(sender.isAllMms)
        assertEquals(0, sender.mmsCount)
    }

    @Test
    fun `skips messages with an unusable address`() {
        val messages = listOf(message(1, "   "), message(2, "22395"))

        assertEquals(1, SenderGrouping.group(messages, "STOP").size)
    }
}
