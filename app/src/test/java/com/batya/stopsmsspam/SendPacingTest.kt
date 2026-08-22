package com.batya.stopsmsspam

import com.batya.stopsmsspam.bulk.SendPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SendPacingTest {

    @Test
    fun `jittered delay stays within the configured spread`() {
        val random = Random(1234)
        repeat(500) {
            val delay = SendPacing.delayMillis(delaySeconds = 10, jitterPercent = 20, random = random)
            assertTrue("too short: $delay", delay >= 8_000)
            assertTrue("too long: $delay", delay <= 12_000)
        }
    }

    @Test
    fun `zero jitter gives the exact delay`() {
        assertEquals(10_000L, SendPacing.delayMillis(10, 0))
        assertEquals(5_000L, SendPacing.delayMillis(5, 0))
    }

    @Test
    fun `never waits less than a second, however aggressive the jitter`() {
        val random = Random(99)
        repeat(500) {
            assertTrue(SendPacing.delayMillis(1, SendPacing.MAX_JITTER_PERCENT, random) >= 1_000)
        }
    }

    @Test
    fun `delay is clamped to the supported range`() {
        assertEquals(SendPacing.MIN_DELAY_SECONDS * 1000L, SendPacing.delayMillis(0, 0))
        assertEquals(SendPacing.MAX_DELAY_SECONDS * 1000L, SendPacing.delayMillis(9999, 0))
    }

    @Test
    fun `the offered delays are the six the user asked for`() {
        assertEquals(listOf(1, 5, 10, 15, 31, 61), SendPacing.DELAY_STEPS)
        assertEquals(1, SendPacing.MIN_DELAY_SECONDS)
        assertEquals(61, SendPacing.MAX_DELAY_SECONDS)
    }

    @Test
    fun `the steps ascend, so the slider and nearestStep behave`() {
        assertEquals(SendPacing.DELAY_STEPS.sorted(), SendPacing.DELAY_STEPS)
        assertEquals(SendPacing.DELAY_STEPS.distinct(), SendPacing.DELAY_STEPS)
    }

    // Why the top step is 61 and not the rounder 60: at 60s exactly the window still admits 31
    // messages against a limit of 30, so a long batch would stall on per-message system dialogs.
    // Rounding this step down would quietly break unattended runs, which is worth a test.
    @Test
    fun `sixty-one is the shortest whole second that clears the framework check`() {
        val big = 10_000
        assertFalse(SendPacing.exceedsFrameworkThrottle(big, 61))
        assertTrue(SendPacing.exceedsFrameworkThrottle(big, 60))
        for (delay in 1 until 61) {
            assertTrue("$delay should still be throttled", SendPacing.exceedsFrameworkThrottle(big, delay))
        }
    }

    @Test
    fun `the default is a step the slider can actually land on`() {
        assertTrue(SendPacing.DEFAULT_DELAY_SECONDS in SendPacing.DELAY_STEPS)
    }

    @Test
    fun `snaps an off-step delay onto the nearest one it offers`() {
        // Values stored when the range was different must not leave the slider between detents -
        // every one of the old Fibonacci steps is such a value now.
        assertEquals(61, SendPacing.nearestStep(60))
        assertEquals(10, SendPacing.nearestStep(10))
        assertEquals(61, SendPacing.nearestStep(300))
        assertEquals(1, SendPacing.nearestStep(0))
        assertEquals(1, SendPacing.nearestStep(2))
        // Exactly between 1 and 5: ties round up, so a stored 3s never becomes a faster batch
        // than the user chose.
        assertEquals(5, SendPacing.nearestStep(3))
        assertEquals(10, SendPacing.nearestStep(8))
        assertEquals(15, SendPacing.nearestStep(13))
        assertEquals(31, SendPacing.nearestStep(34))
        // An exact step is left alone.
        SendPacing.DELAY_STEPS.forEach { assertEquals(it, SendPacing.nearestStep(it)) }
    }

    @Test
    fun `step index points at the snapped value`() {
        assertEquals(0, SendPacing.stepIndexOf(1))
        assertEquals(SendPacing.DELAY_STEPS.lastIndex, SendPacing.stepIndexOf(89))
        assertEquals(SendPacing.DELAY_STEPS.indexOf(61), SendPacing.stepIndexOf(60))
    }

    @Test
    fun `flags batches that would trip the framework throttle`() {
        // A big batch at the fastest step is a burst well past the 30-per-30-minutes check...
        assertTrue(SendPacing.exceedsFrameworkThrottle(count = 40, delaySeconds = SendPacing.MIN_DELAY_SECONDS))
        // ...and the slowest step is the whole reason the range reaches this far: it clears it.
        assertFalse(SendPacing.exceedsFrameworkThrottle(count = 40, delaySeconds = SendPacing.MAX_DELAY_SECONDS))
    }

    @Test
    fun `small batches never trip the throttle regardless of delay`() {
        for (delay in SendPacing.DELAY_STEPS) {
            assertFalse(
                "count=30 delay=$delay",
                SendPacing.exceedsFrameworkThrottle(SendPacing.FRAMEWORK_BURST_LIMIT, delay),
            )
        }
    }

    @Test
    fun `a batch past the framework limit has a safe delay, and it is an offered step`() {
        for (count in listOf(31, 50, 120, 400)) {
            val safe = SendPacing.safeDelaySecondsFor(count)
            assertNotNull("count=$count", safe)
            assertTrue("count=$count safe=$safe not on the slider", safe in SendPacing.DELAY_STEPS)
            assertFalse(
                "count=$count safe=$safe still throttled",
                SendPacing.exceedsFrameworkThrottle(count, safe!!),
            )
        }
    }

    @Test
    fun `the safe delay is the shortest step that works, not just any that does`() {
        val safe = SendPacing.safeDelaySecondsFor(40)!!
        val shorter = SendPacing.DELAY_STEPS.filter { it < safe }
        shorter.forEach {
            assertTrue("$it should still be throttled", SendPacing.exceedsFrameworkThrottle(40, it))
        }
    }

    @Test
    fun `a batch within the framework limit is safe at any supported delay`() {
        val safe = SendPacing.safeDelaySecondsFor(SendPacing.FRAMEWORK_BURST_LIMIT)
        assertEquals(SendPacing.MIN_DELAY_SECONDS, safe)
        assertFalse(SendPacing.exceedsFrameworkThrottle(SendPacing.FRAMEWORK_BURST_LIMIT, safe!!))
    }

    @Test
    fun `estimates run length from the gaps between sends`() {
        assertEquals(0, SendPacing.estimatedDurationSeconds(count = 1, delaySeconds = 10))
        assertEquals(100, SendPacing.estimatedDurationSeconds(count = 11, delaySeconds = 10))
        // The whole point of the narrower range: a realistic batch is now over in seconds.
        assertEquals(45, SendPacing.estimatedDurationSeconds(count = 10, delaySeconds = 5))
    }
}
