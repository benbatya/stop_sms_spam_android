# send-spam: add stop2stop and end2end presets

## What was asked for

Two new presets for `.claude/skills/send-spam`:

1. `stop2stop` — short code, body `"STOP2STOP"`, expected keyword **STOP**
2. `end2end` — long number, body `"End2End"`, expected keyword **END**

## What the run showed, and what changed

The prediction held. Probed directly before touching the device:

```
STOP2STOP                   -> null
End2End                     -> null
Reply STOP2STOP to opt out  -> STOP2STOP (EXPLICIT)
```

So both new presets would have fallen through to the ASSUMED fallback and been flagged
"No opt-out offered" — the opposite of the stated expectation. Adding them as fixtures alone
would have documented a wrong expectation in the table, so the detector was extended.

**The rule added:** a compound of the form `KEYWORD2WORD` — texting shorthand where "2" stands
for "to" — at the *end* of the message yields the leading keyword at LIKELY confidence.

**Anchored to the end of the body, deliberately.** The first cut of this anchored to the whole
body; the user corrected that — these compounds arrive as a suffix on a normal spam message, not
as the entire message. Trailing-token anchoring covers both, since a body that *is* the token is
also trailing.

The anchor still matters, and is not just a formality. Mid-sentence, `End2End` is ordinary
English ("our End2End encrypted chat is live"). A false positive there does more than mislabel a
row: it flips `hasOptOutLanguage` to true, and that flag is what "Select all with opt-out" relies
on to keep the user from replying to scam numbers. A trailing token is a sign-off; the same token
inside a sentence is prose. Covered by tests both ways.

Residual risk, accepted knowingly: a message that happens to *end* on the phrase — "our chat is
End2End" — would be a false positive. Tightening further (requiring preceding sentence
punctuation) would have rejected the realistic `FLASH SALE 50% off everything, today only!
STOP2STOP` shape, so the looser anchor is the right trade.

The third probe line drove one more decision: an explicit instruction still wins, so
`Reply STOP2STOP to opt out` still yields the whole token `STOP2STOP`, not `STOP`. If a sender
names `STOP2STOP` as its keyword, that is what should be sent back — replying `STOP` might not
register. The compound rule only applies when there is no instruction to read.

Verified on the Android 16 emulator with the realistic suffix bodies:
`33733 "FLASH SALE 50% off everything, today only! STOP2STOP"` shows `Reply "STOP" (probable)`,
and `15557654321 "Hi! Are you still looking for work? End2End"` shows `Reply "END" (probable)`.

## Out of scope

- The preset table's other entries.
- Any change to how the app *sends* replies; this is about what it detects.

## Note

Stacked on `260820_restore_sms_role_dialog`, which carries the send-spam role-verification fix
this branch's script edits sit on top of. Must merge bottom-up.
