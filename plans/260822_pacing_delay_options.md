# Pacing delay options: 1, 5, 10, 15, 31, 61 seconds

## What was asked for

> make the pacing delay options 1, 5, 10, 15, 31, or 61 seconds

Replaces the Fibonacci steps (1, 2, 3, 5, 8, 13, 21, 34, 55, 89) that the slider currently
offers. Six detents instead of ten, on round numbers rather than a sequence.

## Approach

`SendPacing.DELAY_STEPS` is the single source: the slider, the throttle warning, the
"safe delay" suggestion and the snapping of stored values all read from it. Changing the list
should be the whole change.

Two things to check rather than assume:

- **`safeDelaySecondsFor` must still have an answer.** It returns the shortest offered step that
  keeps a batch under Android's 30-per-30-minutes check; if no step is long enough it returns
  null and the UI has nothing to suggest.
- **The Fibonacci-specific tests must go**, including the one asserting each step is the sum of
  the two before it. What replaces them should test a property the new list actually has, not be
  deleted quietly.

## What the change turned up

**61 is load-bearing, and 60 would not do.** `safeDelaySecondsFor` still has an answer, but only
just: 61s is the *shortest whole-second delay* that clears Android's outgoing-SMS check for a
batch of any size, and 60s does not - at 60s exactly the window admits
`1800 / 60 + 1` = 31 messages against a limit of 30. One second decides whether a long run
finishes unattended or stalls on per-message system dialogs. Checked by computation rather than
assumed, and now pinned by a test that walks every delay from 1 to 60 and asserts each is still
throttled.

**Ties in `nearestStep` needed a direction.** The old steps included 3, which now sits exactly
between 1 and 5, and `minByOrNull` was resolving such ties to the *first* match - the shorter
delay. A stored 3s would therefore have migrated to 1s and sent five times faster than the user
had chosen. Ties now go to the longer delay: rounding up only costs a slower batch, rounding
down quietly sends faster than was asked for. This surfaced as a failing test whose expectation
was written before the behaviour was checked.

The Fibonacci tests were replaced rather than deleted: the "sum of the two before it" invariant
is gone, and in its place are the ordering property the new list has, the six exact values, and
the 61-versus-60 test above.

## Verified

67 unit tests, `lintDebug` and `assembleDebug` clean. On the Android 12 emulator the pacing
slider shows six detents instead of ten, and dragging to the far end reads
`Delay between replies: 61s`.

## Out of scope

- The jitter control, the default delay, or the throttle warning's wording.
- Migrating stored preferences: `nearestStep` already snaps an off-step value onto the closest
  offered one, which is exactly the case of a delay saved under the old steps.
