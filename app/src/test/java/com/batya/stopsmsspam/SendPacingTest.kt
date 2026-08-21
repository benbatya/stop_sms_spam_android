package com.batya.stopsmsspam

import com.batya.stopsmsspam.bulk.SendPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SendPacingTest {

    @Test
    fun `jittered delay stays within the configured spread`() {
        val random = Random(1234)
        repeat(500) {
            val delay = SendPacing.delayMillis(delaySeconds = 60, jitterPercent = 20, random = random)
            assertTrue("too short: $delay", delay >= 48_000)
            assertTrue("too long: $delay", delay <= 72_000)
        }
    }

    @Test
    fun `zero jitter gives the exact delay`() {
        assertEquals(60_000L, SendPacing.delayMillis(60, 0))
    }

    @Test
    fun `delay is clamped to the supported range`() {
        assertEquals(SendPacing.MIN_DELAY_SECONDS * 1000L, SendPacing.delayMillis(1, 0))
        assertEquals(SendPacing.MAX_DELAY_SECONDS * 1000L, SendPacing.delayMillis(9999, 0))
    }

    @Test
    fun `flags batches that would trip the framework throttle`() {
        // 40 messages five seconds apart is a burst well past the 30-per-30-minutes check.
        assertTrue(SendPacing.exceedsFrameworkThrottle(count = 40, delaySeconds = 5))
        // The same 40 messages a minute apart only fits 30 into the window boundary.
        assertFalse(SendPacing.exceedsFrameworkThrottle(count = 40, delaySeconds = 65))
    }

    @Test
    fun `small batches never trip the throttle regardless of delay`() {
        assertFalse(SendPacing.exceedsFrameworkThrottle(count = 30, delaySeconds = 5))
    }

    @Test
    fun `suggested safe delay actually clears the throttle`() {
        for (count in listOf(31, 50, 120, 400)) {
            val safe = SendPacing.safeDelaySecondsFor(count)
            assertFalse(
                "count=$count delay=$safe still throttled",
                SendPacing.exceedsFrameworkThrottle(count, safe),
            )
        }
    }

    @Test
    fun `estimates run length from the gaps between sends`() {
        assertEquals(0, SendPacing.estimatedDurationSeconds(count = 1, delaySeconds = 60))
        assertEquals(600, SendPacing.estimatedDurationSeconds(count = 11, delaySeconds = 60))
    }
}
