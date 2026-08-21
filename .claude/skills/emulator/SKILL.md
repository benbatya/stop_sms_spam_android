---
name: emulator
description: Boot a Stop SMS Spam test emulator on Android 12, 14 or 16, build and install the debug APK, and grant it the SMS role so the app opens straight to its inbox. Use when asked to start the emulator, run the app on a device, test a specific Android version, reinstall after a change, or set up for manual/visual testing.
---

# Run the app on the test emulator

One command boots an AVD in a window, builds, installs, and grants the SMS role:

```bash
.claude/skills/emulator/scripts/emulator.sh                # Android 16 (default)
.claude/skills/emulator/scripts/emulator.sh --version 12   # Android 12
.claude/skills/emulator/scripts/emulator.sh --version 14   # Android 14
```

It is safe to re-run: if the requested version is already up it skips straight to build +
install, which makes it the normal way to push a code change onto the device. Re-running cannot
change a booted emulator's window mode or Android version — use `--replace` for that.

## Options

| Flag | Effect |
|---|---|
| `--version N` | Android 12, 14 or 16 (default 16) |
| `--avd NAME` | Boot a specific AVD by name, overriding `--version` |
| `--replace` | Shut down whatever emulator is running first, then boot this one |
| `--headless` | Boot with no window, for scripted or screenshot-driven runs |
| `--window` / `--visible` | Show the emulator window (the default) |
| `--no-build` | Install the existing APK without running Gradle |
| `--no-install` | Just boot the emulator |
| `--no-role` | Install without granting the SMS role (to test the setup screen) |
| `--wipe` | Factory-reset the data partition first |

## The three versions, and why each is worth testing

| `--version` | API | AVD | Why this one |
|---|---|---|---|
| 12 | 31 | `android12_generic` | The app's **minSdk floor** — the oldest thing it must run on |
| 14 | 34 | `android14_generic` | Where **foreground-service types** became enforced, which the batch service depends on |
| 16 | 36 | `android16_generic` | The app's **targetSdk** — strictest runtime rules, and where `getDefaultSmsPackage` stopped reflecting the role |

All three are `medium_phone` (vendor-neutral) `google_apis` x86_64 images.

## One emulator at a time, always on port 5554

The script **refuses to boot a second emulator** and exits non-zero if any is already running,
naming the one it found. Two running emulators mean two devices, and every bare
`adb -s emulator-5554` in this repo would silently pick between them — so the port is fixed and
the instance is exclusive.

- Same version **and** window mode already up → reused; straight to build + install.
- A different version up → refused, with the `emu kill` command to run.
- Same version but the other window mode → refused. Window mode is fixed at boot, so reusing
  would make `--visible`/`--headless` look like it worked when nothing changed.
- `--replace` → stops the running one and boots as requested, on the same port.

Because the port never moves, **send-spam needs no version flag** — whatever is on 5554 is the
emulator under test.

## What "granting the role" does, and when not to

The script runs `cmd role add-role-holder android.app.role.SMS` so a test run lands on the
inbox instead of the setup screen. That shortcut is only available to adb — on a real phone
the user grants it through the system dialog.

Pass `--no-role` when the thing being tested *is* the setup flow, the role request, or the
"Restore SMS app" handback.

## After it finishes

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb -s emulator-5554"

$ADB shell am start -n com.batya.stopsmsspam/.MainActivity   # launch
$ADB exec-out screencap -p > shot.png                        # screenshot (works headless)
$ADB shell input tap X Y                                     # drive the UI
$ADB emu kill                                                # shut down
```

Screenshots work whether or not the window is showing, and the Read tool renders them
inline — so a `--headless` emulator is still fully inspectable.

To put messages in the inbox, use the **send-spam** skill.

## Checking what the app actually did

The app keeps no database of its own; the system provider is the source of truth.

```bash
$ADB shell content query --uri content://sms/inbox --projection address:body:read
$ADB shell content query --uri content://sms/sent  --projection address:body
$ADB shell dumpsys activity services com.batya.stopsmsspam | grep isForeground
```

A row in `sms/sent` means the radio confirmed the send and the app filed it. `read=1` on an
inbox row means the post-send cleanup ran.

## Gotchas

- **Short codes may block on a system dialog.** Android asks for per-message confirmation on
  some short codes. Tick "Remember my choice" once, or the batch reports them `unconfirmed`.
- **Tap coordinates shift between API levels and device profiles.** The bottom bar sits lower
  on Android 16 than on 12 because of gesture-nav insets, and `medium_phone` is 1080x2400 where
  `pixel_5` was 1080x2340. Screenshot before tapping rather than reusing offsets.
- **`adb` must be the SDK one** at `$ANDROID_HOME/platform-tools/adb`; `/usr/bin/adb` is an
  older distro build.
