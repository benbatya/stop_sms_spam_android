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

## What was done

`MIN_DELAY_SECONDS = 1`, `MAX_DELAY_SECONDS = 10`, `DEFAULT_DELAY_SECONDS = 5`.

`safeDelaySecondsFor` now returns `Int?`, null when nothing in range clears the throttle,
instead of coercing ~62s down to the maximum and passing it off as safe. `ReviewScreen` shows
the "Use a safe delay" button only when a real answer exists, and otherwise extends the warning
to say plainly that no supported delay avoids the limit and the user should send fewer at a time.

The class doc on `SendPacing` was corrected too: it claimed the delay control addressed both the
carrier and the framework check. It now only addresses the first, and saying otherwise would
have left the next reader believing a guarantee the code no longer makes.

### Tests

Several pacing tests asserted against the old range and were rewritten rather than retuned —
passing 60s or 65s to a control whose maximum is 10s tests something no user can reach. The
suite now pins the range itself, asserts the default is reachable on the slider, and replaces
"suggested safe delay clears the throttle" with the two facts that are actually true now: a
batch within the framework limit is safe at any supported delay, and a batch past it has no safe
delay at all. 37 tests pass, up from 34.

### Verified on the Android 16 emulator

- Fresh install loads the 5s default; slider travels 1s to 10s and nothing outside it.
- "7 replies, roughly 30 seconds" — six gaps at 5s.
- With 39 senders selected, the warning appears with the added sentence and **no**
  "Use a safe delay" button, which is the branch this change introduced.

### Answered

The open question about the default: 5s, mid-range. Easy to change if it feels wrong in use.

## Out of scope

- The jitter range (0–50%) — untouched.
- The framework throttle constants themselves; they describe Android's behaviour, not a policy
  this app chooses.

