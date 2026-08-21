---
name: send-spam
description: Inject fake spam SMS into the running emulator so the Stop SMS Spam app has messages to find. Use when asked to send a test message, add spam to the inbox, populate the emulator with unread texts, or exercise keyword detection and sender grouping.
---

# Send test spam to the emulator

```bash
.claude/skills/send-spam/scripts/send-spam.sh                    # one of every preset
.claude/skills/send-spam/scripts/send-spam.sh --preset repeat    # 3 texts from one sender
.claude/skills/send-spam/scripts/send-spam.sh --from 22395 --body "Reply STOP to opt out"
.claude/skills/send-spam/scripts/send-spam.sh --list             # what is in the inbox now
```

Needs a running emulator — start one with the **emulator** skill.

## Presets, and what each one proves

| Preset | Sender | Expected in the app |
|---|---|---|
| `stop` | short code | keyword `STOP`, confidence explicit |
| `end` | long number | keyword `END` — proves it is not hardcoded to STOP |
| `quit` | short code | keyword `QUIT` from "Txt QUIT to stop receiving" |
| `unsub` | short code | keyword `UNSUB` from "to be removed" |
| `bare` | short code | keyword `STOP` at *likely* confidence, from a shouted bare keyword |
| `stop2stop` | short code | spam ending `... STOP2STOP` — keyword `STOP` at *likely*, from the suffix |
| `end2end` | long number | spam ending `... End2End` — keyword `END` at *likely*, mixed case and all |
| `scam` | long number | **no keyword** — flagged red, "No opt-out offered", excluded from "Select all with opt-out" |
| `repeat` | one short code ×3 | collapses to **one** row, "3 unread messages - one reply covers all of them" |
| `mixed` | all of the above | the default; the general-purpose case |

The last two exercise the trailing-compound rule ("2" as shorthand for "to"), which is how
senders append these — a sign-off rather than a spelled-out instruction. It is anchored to the
**end** of the message deliberately: mid-sentence, `End2End` is ordinary English ("our End2End
encrypted chat is live"), and detecting it there would wrongly mark the sender as offering an
opt-out — which is what "Select all with opt-out" trusts to keep you from replying to scam
numbers. An explicit instruction still wins, so `Reply STOP2STOP to opt out` yields the whole
token `STOP2STOP`, not `STOP`.

`--count N` and `--gap SECONDS` repeat a send, which is how to build a batch large enough to
trip the framework throttle warning (over 30 replies inside 30 minutes).

## Why this is a real test, not a fixture

`adb emu sms send` goes through the emulator's modem and fires a genuine `SMS_DELIVER`
broadcast. While the app holds the SMS role, **the system does not write that message to the
provider — the app must**. So a message appearing in the app's unread list is proof that
`SmsDeliverReceiver` persisted it; nothing else could have.

If a sent message never shows up, that receiver is broken and real texts would be lost.

## Sending nothing real

These messages exist only inside the emulator, and an emulator cannot send SMS to the outside
world. That makes it the right place to run the app with **dry run off** and watch actual
`SmsManager` calls, mark-as-read, and Sent-box logging — without texting a stranger.
