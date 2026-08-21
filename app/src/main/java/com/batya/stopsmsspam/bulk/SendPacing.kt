package com.batya.stopsmsspam.bulk

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Pacing rules for a bulk opt-out run.
 *
 * Two separate things push back on sending fast. The Android framework itself blocks an app
 * that exceeds `SMS_OUTGOING_CHECK_MAX_COUNT` messages inside `SMS_OUTGOING_CHECK_INTERVAL_MS`
 * (30 messages / 30 minutes on stock) and starts prompting per message; carriers separately
 * dislike bursts of identical short messages.
 *
 * The delay control addresses the carrier side directly. It deliberately no longer stretches far
 * enough to clear the framework check: the range tops out at [SendPacing.MAX_DELAY_SECONDS], well
 * under the ~62s a batch of more than [SendPacing.FRAMEWORK_BURST_LIMIT] would need. For batches
 * that large the app warns rather than pretending a supported delay will fix it - see
 * [SendPacing.safeDelaySecondsFor].
 */
object SendPacing {

    const val MIN_DELAY_SECONDS = 1
    const val MAX_DELAY_SECONDS = 10
    const val DEFAULT_DELAY_SECONDS = 5
    const val DEFAULT_JITTER_PERCENT = 20
    const val MAX_JITTER_PERCENT = 50

    /** Stock value of `Settings.Global.SMS_OUTGOING_CHECK_MAX_COUNT`. */
    const val FRAMEWORK_BURST_LIMIT = 30

    /** Stock value of `Settings.Global.SMS_OUTGOING_CHECK_INTERVAL_MS`, in seconds. */
    const val FRAMEWORK_WINDOW_SECONDS = 30 * 60

    /**
     * The wait before the next send: [delaySeconds] varied by up to [jitterPercent] either way,
     * so a batch does not look like a metronome to the carrier.
     */
    fun delayMillis(delaySeconds: Int, jitterPercent: Int, random: Random = Random.Default): Long {
        val base = delaySeconds.coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS) * 1000L
        val jitter = jitterPercent.coerceIn(0, MAX_JITTER_PERCENT)
        if (jitter == 0) return base
        val spread = base * jitter / 100.0
        val offset = (random.nextDouble() * 2.0 - 1.0) * spread
        return max(1000L, (base + offset).roundToLong())
    }

    /** How many messages this pacing would send inside one framework throttle window. */
    fun messagesInThrottleWindow(count: Int, delaySeconds: Int): Int {
        if (count <= 0) return 0
        val effectiveDelay = delaySeconds.coerceAtLeast(1)
        val fitInWindow = FRAMEWORK_WINDOW_SECONDS / effectiveDelay + 1
        return min(count, fitInWindow)
    }

    /**
     * True when this batch would trip Android's own outgoing-SMS check, which makes the system
     * put up a per-message confirmation dialog and stalls an unattended run.
     */
    fun exceedsFrameworkThrottle(count: Int, delaySeconds: Int): Boolean =
        messagesInThrottleWindow(count, delaySeconds) > FRAMEWORK_BURST_LIMIT

    /**
     * Smallest delay that keeps a batch of [count] messages under the framework limit, or null
     * when no delay in the permitted range can.
     *
     * Nullable on purpose. Clearing the throttle for a batch over [FRAMEWORK_BURST_LIMIT] needs
     * roughly a minute between sends, which is outside the range this app offers - so coercing
     * the answer back into range would hand out a delay that does not actually work, and the UI
     * would present it as a fix. Null says plainly that the batch cannot be paced out of
     * trouble, so the caller keeps warning instead of promising a remedy.
     */
    fun safeDelaySecondsFor(count: Int): Int? {
        if (count <= FRAMEWORK_BURST_LIMIT) return MIN_DELAY_SECONDS
        val needed = FRAMEWORK_WINDOW_SECONDS / (FRAMEWORK_BURST_LIMIT - 1) + 1
        return needed.takeIf { it in MIN_DELAY_SECONDS..MAX_DELAY_SECONDS }
    }

    /** Rough wall-clock length of the run, ignoring per-send network time. */
    fun estimatedDurationSeconds(count: Int, delaySeconds: Int): Int =
        if (count <= 1) 0 else (count - 1) * delaySeconds
}
