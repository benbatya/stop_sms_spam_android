# send-spam: add stop2stop and end2end presets

## What was asked for

Two new presets for `.claude/skills/send-spam`:

1. `stop2stop` — short code, body `"STOP2STOP"`, expected keyword **STOP**
2. `end2end` — long number, body `"End2End"`, expected keyword **END**

## The open question these raise

Both bodies bury the keyword inside a larger token, which is exactly what the current detector
is built *not* to do. Reading `OptOutKeywordDetector` before running anything:

- The bare-keyword pattern is `\b(STOPALL|STOP|…)\b`. In `STOP2STOP` there is no word boundary
  between `STOP` and `2` (both are word characters), so `\bSTOP\b` should not match at either
  position.
- `END` is deliberately excluded from the bare-keyword set — a shouted bare "END" is as likely
  to be marketing copy ("SALE ENDS TONIGHT") as an instruction. `End2End` is also mixed case,
  and the bare pattern is case-sensitive.

So the expectation stated in the request is likely **not** what the app currently does: both
should fall through to the ASSUMED fallback and be flagged "No opt-out offered". That makes this
not purely a test-fixture change — the stated expectations are a detector feature request.

**To be settled by running it, not by reading the regexes.** Add the presets, observe what the
app actually shows, and then decide between:

- extending the detector to find keywords inside compound tokens, or
- recording the real behaviour in the preset table as the expectation.

The risk with extending it is false positives: `End2End` is ordinary English ("our end2end
encrypted chat"), and a wrong detection here does not merely mislabel a row — it flips
`hasOptOutLanguage` to true, which is what "Select all with opt-out" trusts to keep the user
from texting scam numbers. Any widening has to be narrow enough not to weaken that.

## Approach

Add the presets first, verify on the emulator, then bring the finding back before changing
detection logic.

## Out of scope

- The preset table's other entries.
- Any change to how the app *sends* replies; this is about what it detects.

## Note

Stacked on `260820_restore_sms_role_dialog`, which carries the send-spam role-verification fix
this branch's script edits sit on top of. Must merge bottom-up.
