# emulator skill: pick an Android version, and run only one at a time

## What was asked for

Two requests, the second of which reversed part of the first:

1. Install `medium_phone` AVDs for Android 12 and 14, and add a parameter to the emulator skill
   to select the version (12, 14 or 16).
2. Then: allow **only one emulator instance** at a time, on a single port, and verify no previous
   instance is running before starting a new one.

Plus a `--visible` alias, because that is the word that gets reached for rather than `--window`.

## What changed

**Three AVDs**, all `medium_phone` (vendor-neutral) `google_apis` x86_64, chosen so each covers a
distinct risk: **12 / API 31** is the app's minSdk floor, **14 / API 34** is where
foreground-service types became enforced (which the batch service depends on), **16 / API 36** is
the targetSdk. `--version 12|14|16` selects between them; `--avd NAME` still overrides.

**One instance, one port.** The first cut gave each version its own console port so two could run
side by side. The single-instance rule made that not just unnecessary but wrong, so it was
removed: everything is on 5554 and the script refuses to boot a second emulator, exiting non-zero
and naming what it found. The reason the guard matters is that two running emulators mean two
devices, and every bare `adb -s emulator-5554` in this repo would then silently pick between them.

The guard checks **any emulator on any port**, not just 5554 — a stray instance elsewhere is
exactly the case that would otherwise slip through.

`--replace` stops whatever is running and boots the requested configuration on the same port.

**Window mode is part of the match, not just the version.** It is fixed at boot, so a request for
a different mode cannot be satisfied by reusing the running instance. Reusing it silently would
make `--visible`/`--headless` look like it worked while nothing changed — the same
flag-that-does-nothing failure this repo has hit before — so a mismatch refuses and points at
`--replace`.

**send-spam lost its version flag.** It briefly took `--version` to resolve the per-version port.
With a fixed port there is nothing to resolve, and a flag that selects nothing is worse than no
flag, so it was removed. `--port` remains as an override.

## A bug found while verifying, worth recording

The first window-mode detection was
`pgrep -af 'qemu-system' | grep -- '-avd $AVD' | grep -q -- '-no-window'`. My own verification
command reported `STILL HEADLESS` for an emulator that was demonstrably windowed: **the shell
command doing the checking contains those flag strings in its own command line, so it matches
itself.** The same false positive had already appeared when checking whether the `spam*` AVDs
were still running.

Detection now reads `/proc/<pid>/cmdline` directly. In a check that gates whether a running
emulator gets killed and restarted, a self-matching grep is not an acceptable failure mode.

## Verified

- `--version 12` and `--version 14` each boot, install, and grant the role; the app runs on both
  with no crashes.
- Guard: same version+mode already up → reused; different version → refused, exit 1; different
  window mode → refused, exit 1; `--replace` → restarts correctly. Both window-mode directions
  tested.
- Full end-to-end on **Android 12**, the minSdk floor: 25 messages grouped into 8 senders, real
  send (dry run off) of 7 replies, **7 sent / 0 failed**, all filed to the Sent box with the
  right per-sender keyword — including `STOP` from a trailing `STOP2STOP` and `END` from
  `End2End` — all marked read, and the scam sender left untouched.
- **Android 14** ran a batch with `types=00000001` (dataSync) accepted under its
  foreground-service type enforcement.

## Also done

Deleted the leftover `spam31` / `spam36` pixel_5 AVDs from earlier testing, after confirming
neither was the running AVD. Reclaimed 3.5 GB.

## Out of scope

- Wiring lint or CI into the emulator skill; it builds and installs, it does not validate.
- The app itself — this branch touches only `.claude/skills/`.
