# Stop SMS Spam

An Android app that lists your unread texts grouped by sender, lets you pick out the spam, and
sends each sender the opt-out keyword it asks for — paced with an adjustable delay so the burst
does not trip Android's own outgoing-SMS limit or annoy your carrier.

Personal sideload only. It uses `READ_SMS`/`SEND_SMS` and the default-SMS-app role, all of which
Google Play restricts to messaging apps.

**Supported: Android 12 (API 31) and up.** Built against API 36.

## What it does

1. **Lists unread SMS, grouped by sender.** A number that texted you eight times is one row and
   gets one reply, not eight.
2. **Detects the opt-out keyword per sender.** Bulk senders are required to state it, but they do
   not all use `STOP` — `END`, `QUIT`, `UNSUB` and `CANCEL` all show up. Each detection carries a
   confidence, and every keyword is editable before sending.
3. **Flags senders that never said how to opt out.** A reply probably will not stop them, and it
   does tell them the number is live. Those rows are marked, and Review counts them in a warning
   before you send. This is the *absence* of an opt-out instruction, not scam detection — the app
   does none. A legitimate sender that omits the line is flagged; a scammer that writes "Reply
   STOP to opt out" is not.
4. **Sends the batch slowly**, with the delay stepped over the Fibonacci sequence — 1, 2, 3, 5, 8,
   13, 21, 34, 55, 89 seconds, default 5 — plus optional random jitter, from a foreground service
   with live progress and a working Cancel. The top of that range clears Android's own
   outgoing-SMS check; the low steps are where a small batch is actually tuned.
5. **Remembers who has already been told to stop**, by reading the message history rather than
   keeping its own record. A sender you have opted out of is cleared rather than texted again,
   and one that acknowledged the opt-out and then messaged anyway is flagged and can be blocked.
6. **Cleans up per sender** — delete by default, keep-and-mark-read on request, block for a sender
   that ignored its own opt-out — and files your reply into the real conversation thread.

### Dry run is on by default

The first run walks through the entire pipeline — pacing, progress, cleanup decisions — without
handing a single message to the radio, and without deleting or blocking anything. Turn it off in
Review when you are satisfied.

## The default-SMS-app role, and why you should hand it back

Marking messages read and filing sent replies into your threads both require `WRITE_SMS`, which
only the default SMS app has. So the app asks for that role.

While it holds the role it is **solely responsible** for incoming messages — the system stops
writing them to the message database and hands them to this app instead.

- **SMS**: handled. `SmsDeliverReceiver` writes every incoming text into the provider and posts a
  notification. Your messages are all still there when you switch back.
- **MMS (picture and group messages): not handled.** Storing MMS means parsing WAP push PDUs and
  fetching parts from the carrier's MMSC — thousands of lines this app deliberately does not
  implement. Incoming MMS are parked unparsed in app-private storage and you get a notification
  saying so. They will not appear in any messaging app.

**So the intended workflow is: take the role → clean up your spam → hand it straight back.**
"Restore SMS app" in the top bar opens the system screen to do it (Android provides no API for an
app to give the role to another app).

## Short codes ask for confirmation, one at a time

Android gates outgoing messages to some short codes behind a system dialog — "*Stop SMS Spam*
would like to send a message to 262966. This may cause charges." — for **each** message. Since
most legitimate bulk senders are short codes, a batch will run straight into this.

**Tick "Remember my choice" on the first dialog** and the rest go through untouched. The Review
screen counts the short codes in your batch and says so before you start.

If the batch outruns you, that sender is reported **unconfirmed**, not failed: Android never told
the app what happened, and the message may still send once you answer. Unconfirmed senders are
deliberately left unread and are not written to the Sent box, so you can re-run them. (A duplicate
`STOP` is harmless; a message silently reported as failed that actually sent is not.)

## Framework rate limit

Android blocks an app after `SMS_OUTGOING_CHECK_MAX_COUNT` messages inside
`SMS_OUTGOING_CHECK_INTERVAL_MS` — 30 messages per 30 minutes on stock — and then puts up a
confirmation dialog for every message after that, which stalls an unattended run. The Review
screen warns when your batch and delay would cross that line and offers a delay that clears it.
A send rejected for this reason reports `RESULT_ERROR_LIMIT_EXCEEDED` with that explanation.

## Building

The toolchain lives entirely in `$HOME` — no root, no Android Studio.

- JDK 21 at `~/.local/share/jdk/current` (pinned via `org.gradle.java.home` in
  `~/.gradle/gradle.properties`, so the system JDK 25 does not break AGP)
- Android SDK at `~/Android/Sdk` (`local.properties` points at it and is gitignored)

```bash
./gradlew testDebugUnitTest     # JVM tests, no device needed
./gradlew lintDebug             # Android lint; must be clean
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Testing without texting anyone

An emulator cannot send SMS to the outside world, so it is the safe place to exercise everything:

```bash
emulator -avd <name> -no-window -no-audio &
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb emu sms send 22395 "FLASH SALE! Reply STOP to opt out"
adb emu sms send 5551234567 "your package is delayed, click bit.ly/x"
adb shell content query --uri content://sms/sent    # what the app filed after a send
```

Injecting a message also verifies the role obligation: if it shows up in the app's unread list,
`SmsDeliverReceiver` persisted it correctly — nothing else could have written it.

Verified end to end on Android 12 (API 31) and Android 16 (API 36) emulators: role acquisition,
incoming-message persistence, grouping, keyword detection, dry run, real sends with mark-as-read
and Sent-box logging, the short-code dialog, and the unconfirmed path.

## Layout

```
data/       provider access, keyword detection, sender grouping, settings
sms/        the default-SMS-app contract: deliver receivers, sender, notifications
bulk/       pacing rules, batch persistence, the foreground service that runs a batch
ui/         Compose screens: Setup -> Inbox -> Review -> Progress
role/       acquiring and handing back ROLE_SMS
```

The system Telephony provider is the only message store; the app keeps no database of its own.
`OptOutKeywordDetector`, `PhoneAddress`, `SenderGrouping` and `SendPacing` are pure Kotlin and
carry the unit tests.
