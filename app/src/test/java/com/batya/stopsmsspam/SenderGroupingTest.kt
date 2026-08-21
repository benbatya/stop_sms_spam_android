package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.SenderGrouping
import com.batya.stopsmsspam.data.model.KeywordConfidence
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
    ) = SpamMessage(
        id = id,
        address = address,
        body = body,
        date = date,
        threadId = 1L,
        subscriptionId = 1,
    )

    @Test
    fun `eight messages from one number become one reply`() {
        val messages = (1L..8L).map { message(it, "+15551234567", date = 100 - it) }

        val senders = SenderGrouping.group(messages, fallbackKeyword = "STOP")

        assertEquals(1, senders.size)
        assertEquals(8, senders.single().messageCount)
        assertEquals(8, senders.single().messageIds.size)
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
    fun `skips messages with an unusable address`() {
        val messages = listOf(message(1, "   "), message(2, "22395"))

        assertEquals(1, SenderGrouping.group(messages, "STOP").size)
    }
}
