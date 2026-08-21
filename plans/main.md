# Stop SMS Spam — initial implementation

Initial commit of the whole app. There was no prior history, so this file records the
decisions behind the first version rather than a delta.

(Written as `plans/master.md` when the trunk was still called `master`; renamed with it.)

## What was asked for

An Android app that lists unread SMS, lets the user select the spam, and bulk-replies with
the opt-out keyword each sender requires (`STOP`/`END`/…), with **an adjustable per-message
delay** so the replies do not overwhelm the carrier. Android 12 and up.

## Decisions taken

**Default SMS app role, not read/send-only.** Chosen by the user over the simpler option.
It is what makes mark-as-read and filing replies into the real conversation threads possible
(both need `WRITE_SMS`). The cost is real and is documented in the app's own setup screen:
while holding the role, the app — not the system — is responsible for persisting every
incoming message.

**MMS is deliberately not implemented.** Storing MMS means parsing WAP push PDUs and fetching
parts from the carrier's MMSC; thousands of lines that a spam-cleanup tool has no business
reimplementing. `MmsDeliverReceiver` parks the raw PDU in app-private storage and notifies the
user rather than silently dropping it. This is why the app pushes the "take the role, clean up,
hand it back" workflow and keeps "Restore SMS app" in the top bar at all times.

**Keyword auto-detected per sender, with a per-message override.** Not every sender uses STOP.
`OptOutKeywordDetector` reports a confidence level, and senders with no opt-out language at all
are flagged and excluded from "Select all with opt-out" — replying to an actual scam confirms
the number is live rather than stopping it.

**Grouped by sender, not per message.** A number that texted eight times gets one reply.

**Dry run defaults on.** The first thing anyone should do with a tool that texts dozens of
strangers is watch it not text anyone.

**minSdk 31.** The user asked for "Android 12 and up"; 31 is that floor exactly and avoids
API-level branching below it.

**Foreground service over WorkManager** for the batch: the run is long, deliberately slow, and
actively watched — it needs live progress and a working Cancel. Queue is persisted to disk so a
killed process can resume rather than silently double-texting.

**Provider is the only store.** No Room, no local message DB — anything marked read or filed to
Sent is immediately visible to whatever messaging app the user returns to.

## Bugs found by testing on real emulators (Android 12 and Android 16)

1. **A timeout was reported as `failed` while the send was still pending.** Android gates some
   short codes behind a per-message confirmation dialog. The batch outran it, wrote the sender
   off as failed, and left the dialog open — so tapping Send would have sent a message the app
   had already reported as failed. Fixed by introducing `SendStatus.UNCONFIRMED` (distinct from
   FAILED: not marked read, not written to Sent), raising the short-code timeout to 180s, and
   warning about short codes in Review before the run starts.
2. **`Telephony.Sms.getDefaultSmsPackage` does not reflect the role on Android 16.** The app sat
   on its setup screen while its receivers were already persisting incoming texts. Fixed by
   checking `RoleManager.isRoleHeld` first, with the legacy lookup as fallback.
3. Bottom bar drawn under the navigation bar (Scaffold does not inset a custom `bottomBar`);
   sliders writing to DataStore on every drag frame; "1 replies" pluralization.

Ruled out as *not* our bug: a clipped status bar on the API 36 pixel_5 AVD — the system Settings
app clips identically, so it is an emulator skin artifact.

## Verification

28 JVM unit tests over the pure logic (`OptOutKeywordDetector`, `PhoneAddress`,
`SenderGrouping`, `SendPacing`). Everything else was exercised on Android 12 (API 31) and
Android 16 (API 36) emulators: role acquisition, incoming-message persistence, grouping,
detection, dry run, real sends with mark-as-read and Sent-box logging, the short-code dialog,
and the unconfirmed path.

`.claude/skills/emulator` and `.claude/skills/send-spam` reproduce that setup in one command.

## Note on this commit

`.gitignore` originally used `/build`, which is root-anchored and would have committed
`app/build` — 138 MB of APKs and intermediates. The patterns are now unanchored (`build/`).
