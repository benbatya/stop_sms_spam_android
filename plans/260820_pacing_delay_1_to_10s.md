# Narrow the pacing delay to 1–10s

## What was asked for

The delay slider currently spans 5–300s, which is far too wide to be usable. Change the range
to **1s–10s**.

## What that collides with

The wide range existed to serve Android's outgoing-SMS check: the framework blocks an app after
`SMS_OUTGOING_CHECK_MAX_COUNT` messages inside `SMS_OUTGOING_CHECK_INTERVAL_MS` (30 per 30
minutes on stock) and then prompts per message, stalling an unattended run. Clearing that for a
batch of more than 30 needs roughly a 62s gap — which the new maximum of 10s cannot reach.

Concretely, three things break rather than just shifting:

1. `DEFAULT_DELAY_SECONDS` is 60, outside the new range entirely. Needs a new value; taking 5s
   (mid-range) unless told otherwise.
2. `safeDelaySecondsFor(count)` computes ~62s and then `coerceIn(MIN, MAX)`s it. Under the new
   maximum it would return 10s and claim that clears the throttle, which is false. Its unit test
   asserts exactly that property and will fail — correctly.
3. `ReviewScreen`'s "Use a safe delay" button calls that function, so it would offer a fix that
   does not fix anything.

**This is a deliberate trade, not a bug to design around.** A 1–10s range means any batch over
~30 senders will trip the framework throttle and the user will get a confirmation dialog per
message. That is an acceptable outcome for the realistic case — a handful of spam senders at a
time — but the app must not *claim* safety it cannot deliver.

## Approach

- `MIN_DELAY_SECONDS = 1`, `MAX_DELAY_SECONDS = 10`, `DEFAULT_DELAY_SECONDS = 5`.
- Make `safeDelaySecondsFor` return `Int?` — null when no delay in the permitted range clears the
  throttle — instead of silently coercing to a value that does not. Show the "Use a safe delay"
  button only when it returns non-null; the throttle warning itself still shows either way.
- Update the pacing tests, including the ones that pass 60s/65s as inputs: those values are no
  longer reachable through the UI, so testing them tests nothing a user can do.

No migration needed for a stored 60s: `SettingsStore.update` already coerces into
`[MIN, MAX]`, so an existing preference lands on the new maximum by itself.

## Out of scope

- The jitter range (0–50%) — untouched.
- The framework throttle constants themselves; they describe Android's behaviour, not a policy
  this app chooses.

## Open question

Whether 5s is the right default. Mid-range and safely above the 1s floor, but the user may want
it at the bottom or top of the range.
