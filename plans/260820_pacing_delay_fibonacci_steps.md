# Pacing delay: Fibonacci steps from 1s to 89s

## The problem

The delay slider spanned 5–300s continuously. Almost all of that travel was useless: the
difference between 200s and 220s is meaningless, while the differences that matter — 1s vs 2s vs
3s — were crammed into the first percent of the track and impossible to hit.

## First attempt, and why it was not enough

The range was narrowed to a flat 1–10s. That fixed the precision problem but broke something
else: Android's outgoing-SMS check blocks an app after `SMS_OUTGOING_CHECK_MAX_COUNT` messages
inside `SMS_OUTGOING_CHECK_INTERVAL_MS` (30 per 30 minutes on stock), and clearing it for a
larger batch needs roughly a 62s gap. A 10s maximum simply cannot reach that, so batches over 30
senders were permanently throttled with no remedy the app could offer. `safeDelaySecondsFor` had
to return null and the UI had to explain that no supported delay would help.

That was an honest handling of a bad trade, not a good outcome.

## What actually lands

The slider is now **stepped**, over the Fibonacci sequence: **1, 2, 3, 5, 8, 13, 21, 34, 55, 89**
— the last term under 100. Default 5s.

This resolves the tension rather than trading one side away. Ten evenly spaced detents put most
of the resolution in the low single digits, where a batch is actually tuned, while the top of the
range reaches 89s — comfortably past the ~62s the framework check needs. A linear slider cannot
do both: to span 1–89 it has to make nearly all of its travel meaningless, which is the original
complaint.

Consequences:

- `safeDelaySecondsFor` is no longer forced to give up. It is now asked of `DELAY_STEPS`
  directly — *the shortest offered step that clears the check* — instead of computing a number
  and clamping it. That makes the answer a value the slider can actually select by construction;
  the older arithmetic-then-clamp version could return a delay that did not work. For any batch
  past the limit the answer is 89s.
- It still returns `Int?`. With the current steps there is always an answer, but whether one
  exists is a property of `DELAY_STEPS`, not a fact worth hard-coding — and the UI already
  handles both branches.
- `SettingsStore` now **snaps** rather than clamps, so a preference stored under an older range
  (60s from the original design, 10s from the first attempt) lands on a real detent instead of
  leaving the thumb between two.
- `SendPacing`'s class doc no longer claims the delay control cannot address the framework
  check — it can again.

## Verified

42 unit tests pass, including that each step really is the sum of the two before it, that the
default is a step the slider can land on, that snapping maps 60→55 and 10→8, and that the safe
delay is the *shortest* step that works rather than merely one that does.

On the Android 16 emulator, with 39 senders selected:

- the slider renders ten detents and lands on 3s / 8s / 21s / 55s at the sampled positions;
- the throttle warning offers **"Use a safe delay (89s)"** with the concrete value;
- tapping it snaps the slider to the last detent and the warning clears, with the estimate
  updating to 56 min 22s (38 gaps × 89s).

## Out of scope

- The jitter slider (0–50%), which stays continuous — its precision genuinely is uniform.
- The framework throttle constants, which describe Android's behaviour rather than a policy this
  app chooses.
