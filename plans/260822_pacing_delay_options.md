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

## Out of scope

- The jitter control, the default delay, or the throttle warning's wording.
- Migrating stored preferences: `nearestStep` already snaps an off-step value onto the closest
  offered one, which is exactly the case of a delay saved under the old steps.
