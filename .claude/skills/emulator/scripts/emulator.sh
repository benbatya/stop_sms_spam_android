#!/usr/bin/env bash
# Boot the test AVD, build the app, install it, and put it in a state where the
# spam-cleanup flow can actually be exercised (SMS role + notifications granted).
set -euo pipefail

# Android version -> AVD. All three are medium_phone (vendor-neutral) google_apis images.
avd_for_version() {
    case "$1" in
        12) echo "android12_generic" ;;   # API 31 - the app's minSdk floor
        14) echo "android14_generic" ;;   # API 34 - foreground-service type enforcement
        16) echo "android16_generic" ;;   # API 36 - the app's targetSdk
        *)  return 1 ;;
    esac
}

AVD="${AVD:-}"
VERSION="${VERSION:-16}"
PKG="com.batya.stopsmsspam"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk/current}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"

HEADLESS="${HEADLESS:-0}"
DO_BUILD=1
DO_INSTALL=1
DO_ROLE=1

usage() {
    cat <<'USAGE'
Usage: emulator.sh [options]

  --version N      Android version to boot: 12, 14 or 16 (default: 16)
  --avd NAME       Boot a specific AVD by name, overriding --version
  --headless       Boot with no window (for scripted/screenshot-driven runs)
  --window         Show the emulator window (this is the default; --visible also works)
  --no-build       Skip ./gradlew assembleDebug, install whatever APK exists
  --no-install     Boot only; do not build or install
  --no-role        Install, but do not grant the SMS role / notifications
  --wipe           Boot with a factory-reset data partition
  --replace        Shut down any running emulator first (only one runs at a time)
  -h, --help       This message

Leaves a booted emulator running in the background. Stop it with:
  $ANDROID_HOME/platform-tools/adb -s emulator-5554 emu kill
USAGE
}

WIPE=""
REPLACE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --avd) AVD="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --window|--visible) HEADLESS=0; shift ;;
        --headless) HEADLESS=1; shift ;;
        --no-build) DO_BUILD=0; shift ;;
        --no-install) DO_INSTALL=0; DO_BUILD=0; DO_ROLE=0; shift ;;
        --no-role) DO_ROLE=0; shift ;;
        --wipe) WIPE="-wipe-data"; shift ;;
        --replace) REPLACE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

if [ -z "$AVD" ]; then
    if ! AVD="$(avd_for_version "$VERSION")"; then
        echo "Unknown Android version '$VERSION' - expected 12, 14 or 16." >&2
        echo "Use --avd NAME to boot something else." >&2
        exit 2
    fi
fi

# Deliberately a single fixed port: only one emulator runs at a time, so every command
# in this repo can assume emulator-5554 rather than hunting for the right serial.
PORT="${PORT:-5554}"
SERIAL="emulator-${PORT}"

if [ ! -x "$ADB" ]; then
    echo "adb not found at $ADB - is ANDROID_HOME right?" >&2
    exit 1
fi

# --- single-instance guard -------------------------------------------------------
# Any emulator, on any port, counts: booting a second one gives two devices that every
# later `adb -s emulator-5554` command would silently pick between.
RUNNING_AVD=""
RUNNING_SERIALS="$("$ADB" devices | awk '/^emulator-/ {print $1}')"

if [ -n "$RUNNING_SERIALS" ]; then
    for s in $RUNNING_SERIALS; do
        name="$("$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
        if [ "$s" = "$SERIAL" ] && [ "$name" = "$AVD" ]; then
            # Right AVD on the right port - but window mode is fixed at boot, so asking for a
            # different one has to restart it. Silently reusing would make --window/--headless
            # look like it worked when nothing changed.
            # Read the emulator's argv straight from /proc rather than grepping `pgrep -af`
            # output: any shell command that mentions these flags matches its own command line,
            # which makes the text-matching version report whatever it was asked to look for.
            running_headless=0
            epid="$(pgrep -f "qemu-system.*$AVD" | head -1)"
            if [ -n "$epid" ] && [ -r "/proc/$epid/cmdline" ]; then
                tr '\0' '\n' < "/proc/$epid/cmdline" | grep -qx -- '-no-window' && running_headless=1
            fi
            if [ "$running_headless" != "$HEADLESS" ]; then
                want="with a window"; have="headless"
                [ "$HEADLESS" = "1" ] && { want="headless"; have="with a window"; }
                if [ "$REPLACE" = "1" ]; then
                    echo "Restarting $s to switch from $have to $want..."
                    "$ADB" -s "$s" emu kill > /dev/null 2>&1 || true
                    sleep 5
                    continue
                fi
                echo "$s is already running $AVD $have, but you asked for $want." >&2
                echo "Window mode is fixed at boot. Re-run with --replace to restart it." >&2
                exit 1
            fi
            RUNNING_AVD="$name"
            continue
        fi
        # Something else is up - a different AVD, or the right one on the wrong port.
        if [ "$REPLACE" = "1" ]; then
            echo "Stopping $s (${name:-unknown})..."
            "$ADB" -s "$s" emu kill > /dev/null 2>&1 || true
        else
            echo "An emulator is already running: $s (${name:-unknown})." >&2
            echo "Only one runs at a time. Stop it first, or pass --replace:" >&2
            echo "  $ADB -s $s emu kill" >&2
            exit 1
        fi
    done
    # Give a killed instance time to release the port before we claim it.
    [ "$REPLACE" = "1" ] && sleep 5
fi
# ---------------------------------------------------------------------------------

if ! "$ADB" devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
    if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
        echo "AVD '$AVD' does not exist. Available:" >&2
        "$EMULATOR" -list-avds >&2
        exit 1
    fi

    echo "Booting $AVD on port $PORT..."
    WINDOW_ARGS=""
    [ "$HEADLESS" = "1" ] && WINDOW_ARGS="-no-window"
    # shellcheck disable=SC2086
    nohup "$EMULATOR" -avd "$AVD" -port "$PORT" $WINDOW_ARGS $WIPE \
        -no-audio -no-boot-anim -gpu swiftshader_indirect \
        > "${TMPDIR:-/tmp}/emulator-${AVD}.log" 2>&1 &

    echo -n "Waiting for boot"
    for _ in $(seq 1 90); do
        if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            echo " - up."
            break
        fi
        echo -n "."
        sleep 5
    done

    if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
        echo >&2
        echo "Emulator did not finish booting. Log: ${TMPDIR:-/tmp}/emulator-${AVD}.log" >&2
        exit 1
    fi
else
    echo "$SERIAL is already running $RUNNING_AVD."
fi

if [ "$DO_BUILD" = "1" ]; then
    echo "Building debug APK..."
    (cd "$REPO" && ./gradlew --quiet assembleDebug)
fi

if [ "$DO_INSTALL" = "1" ]; then
    APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$APK" ] || { echo "No APK at $APK - run without --no-build." >&2; exit 1; }
    echo "Installing $(basename "$APK")..."
    "$ADB" -s "$SERIAL" install -r "$APK"
fi

if [ "$DO_ROLE" = "1" ]; then
    # Skips the on-device setup screen so a test run goes straight to the inbox.
    # On a real phone the user grants these through the system dialogs instead.
    "$ADB" -s "$SERIAL" shell cmd role add-role-holder android.app.role.SMS "$PKG" || true
    "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

    HOLDER=$("$ADB" -s "$SERIAL" shell dumpsys role 2>/dev/null \
        | grep -A2 'name=android.app.role.SMS' | grep holders= | head -1 | tr -d '\r')
    echo "SMS role -> ${HOLDER:-unknown}"
fi

echo
echo "Ready on $SERIAL (Android $("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r'))."
echo "  Launch app:  $ADB -s $SERIAL shell am start -n $PKG/.MainActivity"
echo "  Screenshot:  $ADB -s $SERIAL exec-out screencap -p > shot.png"
echo "  Send spam:   .claude/skills/send-spam/scripts/send-spam.sh --preset mixed"
echo "  Shut down:   $ADB -s $SERIAL emu kill"
