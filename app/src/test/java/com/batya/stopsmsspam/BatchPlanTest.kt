package com.batya.stopsmsspam

import com.batya.stopsmsspam.bulk.SendPacing
import com.batya.stopsmsspam.data.model.ReplyPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPlanTest {

    private fun plan(address: String, sendReply: Boolean) =
        ReplyPlan(address, "STOP", listOf(1L), 1, sendReply)

    @Test
    fun `a plan deletes by default and does not block`() {
        // Deleting is the point of the app; blocking is system-wide and outlives it, so it is
        // never the silent default.
        val plan = ReplyPlan("22395", "STOP", listOf(1L), 1)
        assertTrue(plan.delete)
        assertTrue(!plan.block)
    }

    @Test
    fun `a plan sends a reply by default`() {
        assertTrue(ReplyPlan("22395", "STOP", listOf(1L), 1).sendReply)
    }

    @Test
    fun `only the replying plans are paced`() {
        // Ten selected threads, two of which actually get texted: the run should cost one gap,
        // not nine. Clearing a thread never touches the radio, so waiting after one buys nothing.
        val plans = List(8) { plan("2239$it", sendReply = false) } +
            List(2) { plan("5551000$it", sendReply = true) }

        val paced = plans.count { it.sendReply }

        assertEquals(2, paced)
        assertEquals(5, SendPacing.estimatedDurationSeconds(paced, delaySeconds = 5))
    }

    @Test
    fun `throttle only counts messages that leave the phone`() {
        // 40 threads selected but only 5 texted is nowhere near the framework limit; counting
        // the cleared ones would warn about a burst that never happens.
        val plans = List(35) { plan("2239$it", sendReply = false) } +
            List(5) { plan("5551000$it", sendReply = true) }

        val sending = plans.count { it.sendReply }

        assertTrue(SendPacing.exceedsFrameworkThrottle(plans.size, SendPacing.MIN_DELAY_SECONDS))
        assertTrue(!SendPacing.exceedsFrameworkThrottle(sending, SendPacing.MIN_DELAY_SECONDS))
    }
}
