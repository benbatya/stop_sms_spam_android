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
    fun `the offered delays are the Fibonacci sequence up to the last term under 100`() {
        assertEquals(listOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89), SendPacing.DELAY_STEPS)
        assertEquals(1, SendPacing.MIN_DELAY_SECONDS)
        assertEquals(89, SendPacing.MAX_DELAY_SECONDS)
    }

    @Test
    fun `every step really is the sum of the two before it`() {
        val steps = SendPacing.DELAY_STEPS
        for (i in 2 until steps.size) {
            assertEquals("step $i", steps[i - 2] + steps[i - 1], steps[i])
        }
    }

    @Test
    fun `the default is a step the slider can actually land on`() {
        assertTrue(SendPacing.DEFAULT_DELAY_SECONDS in SendPacing.DELAY_STEPS)
    }

    @Test
    fun `snaps an off-step delay onto the nearest one it offers`() {
        // Values stored when the range was different must not leave the slider between detents.
        assertEquals(55, SendPacing.nearestStep(60))
        assertEquals(8, SendPacing.nearestStep(10))
        assertEquals(89, SendPacing.nearestStep(300))
        assertEquals(1, SendPacing.nearestStep(0))
        // An exact step is left alone.
        SendPacing.DELAY_STEPS.forEach { assertEquals(it, SendPacing.nearestStep(it)) }
    }

    @Test
    fun `step index points at the snapped value`() {
        assertEquals(0, SendPacing.stepIndexOf(1))
        assertEquals(SendPacing.DELAY_STEPS.lastIndex, SendPacing.stepIndexOf(89))
        assertEquals(SendPacing.DELAY_STEPS.indexOf(55), SendPacing.stepIndexOf(60))
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
