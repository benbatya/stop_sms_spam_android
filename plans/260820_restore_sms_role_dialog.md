# Make "Restore SMS app" use the same system dialog as "Become the default SMS app"

## What was asked for

The two role buttons behave differently today:

- **"Become the default SMS app"** (setup screen) fires
  `RoleManager.createRequestRoleIntent(ROLE_SMS)` — a focused system dialog listing the
  eligible SMS apps with radio buttons and a "Set as default" action.
- **"Restore SMS app"** (top bar) fires `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` — the
  general "Default apps" list, from which the user has to find and drill into the SMS entry.

The handback should land the user on the same dialog as the grant, not a settings hierarchy.

## The catch

`createRequestRoleIntent` is very likely **not** usable as-is for the handback. AOSP's
`RequestRoleActivity` short-circuits when the calling package already holds the role — it sets
`RESULT_OK` and finishes without showing any UI. Since "Restore SMS app" is only reachable
*while* the app holds the role, wiring it to the same intent would plausibly make the button do
nothing at all — a worse outcome than today's extra taps.

So the intent that produces that picker for an app that already holds the role has to be
established empirically before anything is wired up. Candidates, in preference order:

1. `RoleManager.ACTION_MANAGE_DEFAULT_APP` + `EXTRA_ROLE_NAME = ROLE_SMS` — opens the picker for
   one specific role. May be `@SystemApi`; if so it is unusable from a normal app.
2. The existing `Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (status quo fallback).

**Open question, to be answered on the emulator, not from memory:** does
`createRequestRoleIntent` show anything when the role is already held, and does
`ACTION_MANAGE_DEFAULT_APP` resolve for a non-system app?

## Approach

Determine the above by launching each candidate intent via `adb am start` against the running
emulator with the app holding the role. Wire `SmsRoleManager.handBackIntent()` to whichever
actually presents the picker, and record in a comment why the obvious-looking
`createRequestRoleIntent` was or was not used — otherwise a future reader will "simplify" it
back to the broken version.

If no candidate works, leave the behaviour as-is and say so rather than shipping a dead button.

## Out of scope

- Any change to the grant path ("Become the default SMS app") — it already works.
- Actually assigning the role to another app programmatically. Android has no such API; the
  handback is necessarily a user action in system UI.

## Note

Carries an unrelated committed-later fix to `.claude/skills/send-spam/scripts/send-spam.sh`
(it now verifies which app holds the SMS role before claiming the receiver persisted anything),
at the user's request to bring it onto this branch rather than trunk.
