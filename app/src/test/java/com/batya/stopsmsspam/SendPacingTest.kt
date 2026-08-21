package com.batya.stopsmsspam

import com.batya.stopsmsspam.bulk.SendPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the supported range is the narrow one the UI offers`() {
        assertEquals(1, SendPacing.MIN_DELAY_SECONDS)
        assertEquals(10, SendPacing.MAX_DELAY_SECONDS)
        assertTrue(
            "default must be reachable on the slider",
            SendPacing.DEFAULT_DELAY_SECONDS in
                SendPacing.MIN_DELAY_SECONDS..SendPacing.MAX_DELAY_SECONDS,
        )
    }

    @Test
    fun `flags batches that would trip the framework throttle`() {
        // Every delay the app offers fits far more than the limit into a 30-minute window, so
        // any batch past it is throttled no matter how the slider is set.
        assertTrue(SendPacing.exceedsFrameworkThrottle(count = 40, delaySeconds = SendPacing.MIN_DELAY_SECONDS))
        assertTrue(SendPacing.exceedsFrameworkThrottle(count = 40, delaySeconds = SendPacing.MAX_DELAY_SECONDS))
    }

    @Test
    fun `small batches never trip the throttle regardless of delay`() {
        for (delay in SendPacing.MIN_DELAY_SECONDS..SendPacing.MAX_DELAY_SECONDS) {
            assertFalse(
                "count=30 delay=$delay",
                SendPacing.exceedsFrameworkThrottle(SendPacing.FRAMEWORK_BURST_LIMIT, delay),
            )
        }
    }

    @Test
    fun `there is no safe delay for a batch bigger than the framework limit`() {
        // The deliberate cost of the narrow range: clearing the throttle needs roughly a minute
        // between sends, which the app no longer offers. Reporting null keeps the UI from
        // offering a "fix" that changes the number without fixing anything.
        for (count in listOf(31, 50, 120, 400)) {
            assertNull("count=$count", SendPacing.safeDelaySecondsFor(count))
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
