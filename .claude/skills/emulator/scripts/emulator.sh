#!/usr/bin/env bash
# Boot the test AVD, build the app, install it, and put it in a state where the
# spam-cleanup flow can actually be exercised (SMS role + notifications granted).
set -euo pipefail

AVD="${AVD:-android16_generic}"
PORT="${PORT:-5554}"
SERIAL="emulator-${PORT}"
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

  --avd NAME       AVD to boot (default: android16_generic, or $AVD)
  --headless       Boot with no window (for scripted/screenshot-driven runs)
  --window         Show the emulator window (this is the default)
  --no-build       Skip ./gradlew assembleDebug, install whatever APK exists
  --no-install     Boot only; do not build or install
  --no-role        Install, but do not grant the SMS role / notifications
  --wipe           Boot with a factory-reset data partition
  -h, --help       This message

Leaves a booted emulator running in the background. Stop it with:
  $ANDROID_HOME/platform-tools/adb -s emulator-5554 emu kill
USAGE
}

WIPE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --avd) AVD="$2"; shift 2 ;;
        --window) HEADLESS=0; shift ;;
        --headless) HEADLESS=1; shift ;;
        --no-build) DO_BUILD=0; shift ;;
        --no-install) DO_INSTALL=0; DO_BUILD=0; DO_ROLE=0; shift ;;
        --no-role) DO_ROLE=0; shift ;;
        --wipe) WIPE="-wipe-data"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

if [ ! -x "$ADB" ]; then
    echo "adb not found at $ADB - is ANDROID_HOME right?" >&2
    exit 1
fi

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
    echo "$SERIAL is already running."
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
