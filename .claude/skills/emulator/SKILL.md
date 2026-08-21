---
name: emulator
description: Boot the Stop SMS Spam test emulator, build and install the debug APK, and grant it the SMS role so the app opens straight to its inbox. Use when asked to start the emulator, run the app on a device, reinstall after a change, or set up for manual/visual testing.
---

# Run the app on the test emulator

One command boots the AVD in a window, builds, installs, and grants the SMS role:

```bash
.claude/skills/emulator/scripts/emulator.sh
```

It is safe to re-run: if the emulator is already up it skips straight to build + install,
which makes it the normal way to push a code change onto the device. Note that re-running
against an already-booted emulator cannot change its window mode — kill it first if you need
to switch.

## Options

| Flag | Effect |
|---|---|
| `--avd NAME` | Boot a different AVD (default `android16_generic`) |
| `--headless` | Boot with no window, for scripted or screenshot-driven runs |
| `--no-build` | Install the existing APK without running Gradle |
| `--no-install` | Just boot the emulator |
| `--no-role` | Install without granting the SMS role (to test the setup screen) |
| `--wipe` | Factory-reset the data partition first |

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
- **Tap coordinates shift between API levels.** The bottom bar sits lower on Android 16 than
  on 12 because of gesture-nav insets. Screenshot before tapping rather than reusing offsets.
- **`adb` must be the SDK one** at `$ANDROID_HOME/platform-tools/adb`; `/usr/bin/adb` is an
  older distro build.
