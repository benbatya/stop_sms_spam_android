package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.ContactFilter
import com.batya.stopsmsspam.data.SenderGrouping
import com.batya.stopsmsspam.data.model.SpamMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whose messages a batch is allowed to delete. Worth testing directly:
 * everything downstream trusts the surviving list to contain nobody the user knows.
 */
class ContactFilterTest {

    private fun senders(vararg addresses: String) = SenderGrouping.group(
        addresses.mapIndexed { i, address ->
            SpamMessage(
                id = i.toLong(),
                address = address,
                body = "Reply STOP to opt out",
                date = i.toLong(),
                threadId = 1L,
                subscriptionId = 1,
            )
        },
        "STOP",
    )

    @Test
    fun `drops a sender that is in contacts`() {
        val result = ContactFilter.exclude(senders("+18022160869", "22395"), setOf("+18022160869"))

        assertEquals(listOf("22395"), result.map { it.displayAddress })
    }

    // The whole reason the lookup goes through PhoneLookup rather than a string compare: the
    // contact and the message rarely agree on formatting, and a miss here deletes a person's
    // thread.
    @Test
    fun `matches across formatting differences`() {
        val result = ContactFilter.exclude(senders("+18022160869"), setOf("(802) 216-0869"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `keeps everyone when contacts turned up nothing`() {
        val all = senders("22395", "+18022160869")

        assertEquals(all.size, ContactFilter.exclude(all, emptySet()).size)
    }

    // An empty set is also what the lookup returns when READ_CONTACTS was declined. It must mean
    // "filtered nobody", never "nobody is a contact" - the UI is what tells the user the
    // difference, and it can only do that if this stays a plain no-op.
    @Test
    fun `an unusable contact address does not silently drop everyone`() {
        val all = senders("22395")

        assertEquals(all.size, ContactFilter.exclude(all, setOf("   ")).size)
    }
}
