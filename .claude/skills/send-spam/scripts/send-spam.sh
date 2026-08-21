#!/usr/bin/env bash
# Inject fake spam SMS into a running emulator, so the app has something to find.
#
# Uses the emulator console (`adb emu sms send`), which delivers a real SMS_DELIVER
# broadcast - the same path a carrier message takes. That means it also exercises
# the app's obligation, as default SMS app, to persist the message itself.
set -euo pipefail

PORT="${PORT:-5554}"
SERIAL="emulator-${PORT}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

FROM=""
BODY=""
PRESET=""
COUNT=1
GAP=1

usage() {
    cat <<'USAGE'
Usage: send-spam.sh [--preset NAME | --from NUMBER --body "TEXT"] [--count N] [--gap SECONDS]

Presets:
  stop        short code, "Reply STOP to opt out"        (keyword STOP, explicit)
  end         long number, "Text END to cancel"          (keyword END)
  quit        short code, "Txt QUIT to stop receiving"   (keyword QUIT)
  unsub       short code, "Reply UNSUB to be removed"    (keyword UNSUB)
  bare        short code, shouted "STOP" only            (keyword STOP, likely)
  scam        long number, no opt-out language           (flagged "no opt-out offered")
  repeat      3 messages from ONE short code             (tests sender grouping)
  mixed       one of each of the above                   (the general-purpose case)

Options:
  --from NUMBER     originating address (short code or phone number)
  --body "TEXT"     message body
  --count N         send the same message N times (default 1)
  --gap SECONDS     delay between sends (default 1)
  --list            show what is currently in the inbox and exit
  -h, --help        this message
USAGE
}

send_one() {
    local from="$1" body="$2"
    "$ADB" -s "$SERIAL" emu sms send "$from" "$body" > /dev/null
    printf '  %-14s %s\n' "$from" "$body"
}

send_preset() {
    case "$1" in
        stop)   send_one 22395       "FLASH SALE 50% off everything! Reply STOP to opt out" ;;
        end)    send_one 15559998888 "Hi! Are you still looking for work? Reply END to cancel." ;;
        quit)   send_one 262966      "AMZN: your code is 449281. Txt QUIT to stop receiving alerts" ;;
        unsub)  send_one 43733       "Daily deals from BargainCo. Reply UNSUB to be removed" ;;
        bare)   send_one 55411       "ACCT ALERT: balance low. Msg&data rates may apply. STOP" ;;
        scam)   send_one 15551234567 "Your package is delayed. Reschedule: bit.ly/x9f2" ;;
        repeat)
            send_one 22395 "FLASH SALE 50% off everything! Reply STOP to opt out"
            sleep 1
            send_one 22395 "Did you see our sale? Reply STOP to opt out"
            sleep 1
            send_one 22395 "LAST CHANCE, ends tonight! Reply STOP to opt out"
            ;;
        mixed)
            for p in stop end quit unsub bare scam; do send_preset "$p"; sleep 1; done
            ;;
        *) echo "unknown preset: $1" >&2; usage; exit 2 ;;
    esac
}

LIST_ONLY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --preset) PRESET="$2"; shift 2 ;;
        --from) FROM="$2"; shift 2 ;;
        --body) BODY="$2"; shift 2 ;;
        --count) COUNT="$2"; shift 2 ;;
        --gap) GAP="$2"; shift 2 ;;
        --list) LIST_ONLY=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

if ! "$ADB" devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
    echo "No running emulator at $SERIAL." >&2
    echo "Start one with: .claude/skills/emulator/scripts/emulator.sh" >&2
    exit 1
fi

if [ "$LIST_ONLY" = "1" ]; then
    "$ADB" -s "$SERIAL" shell content query --uri content://sms/inbox \
        --projection _id:address:body:read
    exit 0
fi

if [ -n "$PRESET" ] && { [ -n "$FROM" ] || [ -n "$BODY" ]; }; then
    echo "Use either --preset or --from/--body, not both." >&2
    exit 2
fi

if [ -z "$PRESET" ] && { [ -z "$FROM" ] || [ -z "$BODY" ]; }; then
    # Nothing specified: the mixed set is the useful default for exercising the app.
    PRESET="mixed"
fi

PKG="com.batya.stopsmsspam"

role_holder() {
    "$ADB" -s "$SERIAL" shell dumpsys role 2>/dev/null \
        | grep -A3 'name=android.app.role.SMS' | grep -o 'holders=.*' | head -1 \
        | cut -d= -f2 | tr -d '\r'
}

# Validate the preset before announcing anything, so a typo fails cleanly.
if [ -n "$PRESET" ]; then
    case "$PRESET" in
        stop|end|quit|unsub|bare|scam|repeat|mixed) ;;
        *) echo "unknown preset: $PRESET" >&2; usage; exit 2 ;;
    esac
fi

HOLDER="$(role_holder)"
if [ "$HOLDER" != "$PKG" ]; then
    echo "WARNING: the SMS role is held by '${HOLDER:-unknown}', not $PKG." >&2
    echo "         Incoming messages will be stored by that app, so this run does NOT" >&2
    echo "         exercise SmsDeliverReceiver. Re-grant with:" >&2
    echo "           .claude/skills/emulator/scripts/emulator.sh" >&2
    echo "         (the role does not survive an emulator reboot)" >&2
    echo >&2
fi

echo "Sending to $SERIAL:"
for i in $(seq 1 "$COUNT"); do
    [ "$i" -gt 1 ] && sleep "$GAP"
    if [ -n "$PRESET" ]; then send_preset "$PRESET"; else send_one "$FROM" "$BODY"; fi
done

sleep 2
UNREAD=$("$ADB" -s "$SERIAL" shell content query --uri content://sms/inbox \
    --projection _id --where "read=0" 2>/dev/null | grep -c '^Row:' || true)
echo
echo "Inbox now has ${UNREAD} unread message(s)."
if [ "$HOLDER" = "$PKG" ]; then
    echo "$PKG holds the SMS role, so those rows exist only because its"
    echo "SmsDeliverReceiver wrote them - nothing else could have."
else
    echo "NOTE: '${HOLDER:-unknown}' holds the SMS role, so IT stored those rows."
    echo "This says nothing about whether $PKG's receiver works."
fi
