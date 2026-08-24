package com.batya.stopsmsspam

import com.batya.stopsmsspam.data.SenderExclusion
import com.batya.stopsmsspam.data.SenderGrouping
import com.batya.stopsmsspam.data.model.SpamMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whose messages a batch is allowed to delete. Worth testing directly:
 * everything downstream trusts the surviving list to contain nobody the user knows.
 */
class SenderExclusionTest {

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
        val result = SenderExclusion.exclude(senders("+18022160869", "22395"), setOf("+18022160869"))

        assertEquals(listOf("22395"), result.map { it.displayAddress })
    }

    // The whole reason the lookup goes through PhoneLookup rather than a string compare: the
    // contact and the message rarely agree on formatting, and a miss here deletes a person's
    // thread.
    @Test
    fun `matches across formatting differences`() {
        val result = SenderExclusion.exclude(senders("+18022160869"), setOf("(802) 216-0869"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `keeps everyone when contacts turned up nothing`() {
        val all = senders("22395", "+18022160869")

        assertEquals(all.size, SenderExclusion.exclude(all, emptySet()).size)
    }

    // An empty set is also what the lookup returns when READ_CONTACTS was declined. It must mean
    // "filtered nobody", never "nobody is a contact" - the UI is what tells the user the
    // difference, and it can only do that if this stays a plain no-op.
    // The blocked-sender filter needs the complement, to say how many it is hiding.
    @Test
    fun `matching returns exactly what exclude removes`() {
        val all = senders("+18022160869", "22395", "33733")
        val addresses = setOf("+18022160869", "33733")

        val kept = SenderExclusion.exclude(all, addresses)
        val removed = SenderExclusion.matching(all, addresses)

        assertEquals(listOf("22395"), kept.map { it.displayAddress })
        assertEquals(listOf("+18022160869", "33733"), removed.map { it.displayAddress })
        assertEquals(all.size, kept.size + removed.size)
    }

    @Test
    fun `matching finds nothing when the address set is empty`() {
        assertTrue(SenderExclusion.matching(senders("22395"), emptySet()).isEmpty())
    }

    @Test
    fun `an unusable contact address does not silently drop everyone`() {
        val all = senders("22395")

        assertEquals(all.size, SenderExclusion.exclude(all, setOf("   ")).size)
    }
}
