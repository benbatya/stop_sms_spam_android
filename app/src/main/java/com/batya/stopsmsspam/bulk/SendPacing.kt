package com.batya.stopsmsspam.bulk

import kotlin.math.abs
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
 * The delay control addresses both, but they want very different numbers: a couple of seconds is
 * plenty of courtesy to a carrier, while clearing the framework check for a batch over
 * [SendPacing.FRAMEWORK_BURST_LIMIT] needs about a minute. [SendPacing.DELAY_STEPS] spans that
 * spread without a slider whose travel is mostly wasted - see its own note.
 */
object SendPacing {

    /**
     * The delays the slider offers, in seconds: Fibonacci from 1 up to 89, the last term under
     * 100.
     *
     * Stepped rather than continuous because the useful precision is not uniform. One second
     * versus two is a real difference; 55 versus 56 is not. Fibonacci spacing puts most of the
     * detents where a batch is actually tuned - the low single digits - while still reaching the
     * ~62s needed to clear Android's outgoing-SMS check. A linear slider cannot do both: to span
     * that range it has to make almost all of its travel meaningless.
     */
    val DELAY_STEPS = listOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89)

    val MIN_DELAY_SECONDS = DELAY_STEPS.first()
    val MAX_DELAY_SECONDS = DELAY_STEPS.last()
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
     * The shortest offered delay that keeps a batch of [count] messages under the framework
     * limit, or null if no step is long enough.
     *
     * Asked of [DELAY_STEPS] directly rather than derived arithmetically, so the answer is by
     * construction a delay the slider can actually select - the previous version computed a
     * value and then clamped it, which could hand back a delay that did not clear the check.
     * With the current steps there is always an answer (89s), but the null case is kept because
     * whether one exists is a property of [DELAY_STEPS], not a fact to hard-code.
     */
    fun safeDelaySecondsFor(count: Int): Int? =
        DELAY_STEPS.firstOrNull { !exceedsFrameworkThrottle(count, it) }

    /**
     * Snaps an arbitrary delay onto the nearest offered step, for values that predate the
     * current steps - a preference stored when the range was different, or a resumed batch.
     */
    fun nearestStep(seconds: Int): Int = DELAY_STEPS.minByOrNull { abs(it - seconds) } ?: DEFAULT_DELAY_SECONDS

    /** Position of [seconds] on the slider, after snapping. */
    fun stepIndexOf(seconds: Int): Int = DELAY_STEPS.indexOf(nearestStep(seconds)).coerceAtLeast(0)

    /** Rough wall-clock length of the run, ignoring per-send network time. */
    fun estimatedDurationSeconds(count: Int, delaySeconds: Int): Int =
        if (count <= 1) 0 else (count - 1) * delaySeconds
}
