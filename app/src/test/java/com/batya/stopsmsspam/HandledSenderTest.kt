package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.SenderGrouping
import com.batya.stopsmsspam.data.model.SpamMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thread ids have to survive grouping, or the archived filter has nothing to match on.
 *
 * This is the half that was missing when the filter matched only the blocked list and hid nothing
 * at all: on a real phone 831 of 925 threads holding unread messages were archived, and none of
 * 875 senders were blocked.
 */
class HandledSenderTest {

    private fun message(id: Long, address: String, threadId: Long) = SpamMessage(
        id = id,
        address = address,
        body = "SALE! Reply STOP to opt out",
        date = id,
        threadId = threadId,
        subscriptionId = 1,
    )

    @Test
    fun `a sender carries the thread its messages came from`() {
        val sender = SenderGrouping.group(listOf(message(1, "22395", threadId = 77)), "STOP").single()

        assertEquals(setOf(77L), sender.threadIds)
    }

    @Test
    fun `messages from one sender across two threads keep both`() {
        val messages = listOf(message(1, "22395", 77), message(2, "22395", 88))

        val sender = SenderGrouping.group(messages, "STOP").single()

        assertEquals(setOf(77L, 88L), sender.threadIds)
    }

    // The rule is "all of them", not "any of them". A sender with one archived thread and one
    // live one still has somewhere the user is reading, so hiding it would lose a real message.
    @Test
    fun `a sender is only fully archived when every thread is`() {
        val archived = setOf(77L)
        val mixed = SenderGrouping.group(
            listOf(message(1, "22395", 77), message(2, "22395", 88)),
            "STOP",
        ).single()
        val allArchived = SenderGrouping.group(listOf(message(3, "33733", 77)), "STOP").single()

        assertTrue(mixed.threadIds.any { it in archived })
        assertEquals(false, mixed.threadIds.all { it in archived })
        assertTrue(allArchived.threadIds.all { it in archived })
    }
}
